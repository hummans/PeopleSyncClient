/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.messageconcept.peoplesyncclient

import android.accounts.Account
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Transaction
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.UrlUtils
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.*
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.model.Collection
import com.messageconcept.peoplesyncclient.model.HomeSet
import com.messageconcept.peoplesyncclient.model.Service
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.messageconcept.peoplesyncclient.ui.DebugInfoActivity
import com.messageconcept.peoplesyncclient.ui.NotificationUtils
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.*
import java.util.logging.Level
import kotlin.concurrent.thread

class DavService: android.app.Service() {

    companion object {
        const val ACTION_REFRESH_COLLECTIONS = "refreshCollections"
        const val EXTRA_DAV_SERVICE_ID = "davServiceID"

        /** Initialize a forced synchronization. Expects intent data
            to be an URI of this format:
            contents://<authority>/<account.type>/<account name>
         **/
        const val ACTION_FORCE_SYNC = "forceSync"

        val DAV_COLLECTION_PROPERTIES = arrayOf(
                ResourceType.NAME,
                CurrentUserPrivilegeSet.NAME,
                DisplayName.NAME,
                AddressbookDescription.NAME, SupportedAddressData.NAME,
                CalendarDescription.NAME, CalendarColor.NAME, SupportedCalendarComponentSet.NAME,
                Source.NAME
        )

    }

    private val runningRefresh = HashSet<Long>()
    private val refreshingStatusListeners = LinkedList<WeakReference<RefreshingStatusListener>>()


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val id = intent.getLongExtra(EXTRA_DAV_SERVICE_ID, -1)

            when (intent.action) {
                ACTION_REFRESH_COLLECTIONS ->
                    if (runningRefresh.add(id)) {
                        refreshingStatusListeners.forEach { listener ->
                            listener.get()?.onDavRefreshStatusChanged(id, true)
                        }
                        thread { refreshCollections(id) }
                    }

                ACTION_FORCE_SYNC -> {
                    val uri = intent.data!!
                    val authority = uri.authority!!
                    val account = Account(
                            uri.pathSegments[1],
                            uri.pathSegments[0]
                    )
                    forceSync(authority, account)
                }
            }
        }

        return START_NOT_STICKY
    }


    /* BOUND SERVICE PART
       for communicating with the activities
    */

    interface RefreshingStatusListener {
        fun onDavRefreshStatusChanged(id: Long, refreshing: Boolean)
    }

    private val binder = InfoBinder()

    inner class InfoBinder: Binder() {
        fun isRefreshing(id: Long) = runningRefresh.contains(id)

        fun addRefreshingStatusListener(listener: RefreshingStatusListener, callImmediateIfRunning: Boolean) {
            refreshingStatusListeners += WeakReference<RefreshingStatusListener>(listener)
            if (callImmediateIfRunning)
                runningRefresh.forEach { id -> listener.onDavRefreshStatusChanged(id, true) }
        }

        fun removeRefreshingStatusListener(listener: RefreshingStatusListener) {
            val iter = refreshingStatusListeners.iterator()
            while (iter.hasNext()) {
                val item = iter.next().get()
                if (listener == item)
                    iter.remove()
            }
        }
    }

    override fun onBind(intent: Intent?) = binder



    /* ACTION RUNNABLES
       which actually do the work
     */

    private fun forceSync(authority: String, account: Account) {
        Logger.log.info("Forcing $authority synchronization of $account")
        val extras = Bundle(2)
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)        // manual sync
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)     // run immediately (don't queue)
        ContentResolver.requestSync(account, authority, extras)
    }

    private fun refreshCollections(serviceId: Long) {
        val db = AppDatabase.getInstance(this)
        val homeSetDao = db.homeSetDao()
        val collectionDao = db.collectionDao()

        val service = db.serviceDao().get(serviceId) ?: throw IllegalArgumentException("Service not found")
        val account = Account(service.accountName, getString(R.string.account_type))

        val homeSets = homeSetDao.getByService(serviceId)
                .map { it.url }
                .toMutableSet()

        val collections = mutableMapOf<HttpUrl, Collection>()
        collectionDao.getByService(serviceId).forEach {
            collections[it.url] = it
        }

        /**
         * Checks if the given URL defines home sets and adds them to the home set list.
         *
         * @throws IOException
         * @throws HttpException
         * @throws DavException
         */
        fun queryHomeSets(client: OkHttpClient, url: HttpUrl, recurse: Boolean = true) {
            val related = mutableSetOf<HttpUrl>()

            fun findRelated(root: HttpUrl, dav: Response) {
                // refresh home sets: calendar-proxy-read/write-for
                dav[CalendarProxyReadFor::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is a read-only proxy for $href, checking for home sets")
                        root.resolve(href)?.let { proxyReadFor ->
                            related += proxyReadFor
                        }
                    }
                }
                dav[CalendarProxyWriteFor::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is a read/write proxy for $href, checking for home sets")
                        root.resolve(href)?.let { proxyWriteFor ->
                            related += proxyWriteFor
                        }
                    }
                }

                // refresh home sets: direct group memberships
                dav[GroupMembership::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is member of group $href, checking for home sets")
                        root.resolve(href)?.let { groupMembership ->
                            related += groupMembership
                        }
                    }
                }
            }

            val dav = DavResource(client, url)
            when (service.type) {
                Service.TYPE_CARDDAV ->
                    try {
                        dav.propfind(0, AddressbookHomeSet.NAME, GroupMembership.NAME) { response, _ ->
                            response[AddressbookHomeSet::class.java]?.let { homeSet ->
                                for (href in homeSet.hrefs)
                                    dav.location.resolve(href)?.let {
                                        homeSets += UrlUtils.withTrailingSlash(it)
                                    }
                            }

                            if (recurse)
                                findRelated(dav.location, response)
                        }
                    } catch (e: HttpException) {
                        if (e.code/100 == 4)
                            Logger.log.log(Level.INFO, "Ignoring Client Error 4xx while looking for addressbook home sets", e)
                        else
                            throw e
                    }
                Service.TYPE_CALDAV -> {
                    try {
                        dav.propfind(0, CalendarHomeSet.NAME, CalendarProxyReadFor.NAME, CalendarProxyWriteFor.NAME, GroupMembership.NAME) { response, _ ->
                            response[CalendarHomeSet::class.java]?.let { homeSet ->
                                for (href in homeSet.hrefs)
                                    dav.location.resolve(href)?.let {
                                        homeSets += UrlUtils.withTrailingSlash(it)
                                    }
                            }

                            if (recurse)
                                findRelated(dav.location, response)
                        }
                    } catch (e: HttpException) {
                        if (e.code/100 == 4)
                            Logger.log.log(Level.INFO, "Ignoring Client Error 4xx while looking for calendar home sets", e)
                        else
                            throw e
                    }
                }
            }

            for (resource in related)
                queryHomeSets(client, resource, false)
        }

        @Transaction
        fun saveHomesets() {
            val oldHomesets = homeSetDao.getByService(serviceId)
            oldHomesets.forEach { oldHomeset ->
                val url = oldHomeset.url
                if (homeSets.contains(url))
                    // URL is the same in oldHomesets and newHomesets, remove from "homesets" (which will be added later)
                    homeSets.remove(url)
                else
                    // URL is not in newHomesets, delete from database
                    homeSetDao.delete(oldHomeset)
            }
            // insert new homesets
            homeSetDao.insert(homeSets.map { url ->
                HomeSet(0, serviceId, url)
            })
        }

        @Transaction
        fun saveCollections() {
            val oldCollections = collectionDao.getByService(serviceId)
            oldCollections.forEach { oldCollection ->
                val url = oldCollection.url
                val matchingNewCollection = collections[url]
                if (matchingNewCollection != null) {
                    // old URL exists in newCollections, update database if content has been changed
                    matchingNewCollection.id = oldCollection.id
                    matchingNewCollection.serviceId = oldCollection.serviceId
                    if (matchingNewCollection != oldCollection)
                        collectionDao.update(matchingNewCollection)

                    // remove from "collections" (which will be added later)
                    collections.remove(url)
                } else
                    // URL is not in newCollections, delete from database
                    collectionDao.delete(oldCollection)
            }
            // insert new collections
            collections.forEach { (_, collection) ->
                collection.serviceId = serviceId
            }
            collectionDao.insert(collections.values.toList())
        }

        fun saveResults() {
            saveHomesets()
            saveCollections()
        }

        try {
            Logger.log.info("Refreshing ${service.type} collections of service #$service")

            // cancel previous notification
            NotificationManagerCompat.from(this)
                    .cancel(service.toString(), NotificationUtils.NOTIFY_REFRESH_COLLECTIONS)

            // create authenticating OkHttpClient (credentials taken from account settings)
            HttpClient.Builder(this, AccountSettings(this, account))
                    .setForeground(true)
                    .build().use { client ->
                val httpClient = client.okHttpClient

                // refresh home set list (from principal)
                service.principal?.let { principalUrl ->
                    Logger.log.fine("Querying principal $principalUrl for home sets")
                    queryHomeSets(httpClient, principalUrl)
                }

                // remember selected collections
                val selectedCollections = HashSet<HttpUrl>()
                val deselectedCollections = HashSet<HttpUrl>()
                collections.forEach { (url, collection) ->
                    if (collection.sync)
                        selectedCollections += url
                    else
                        deselectedCollections += url
                }

                // now refresh collections (taken from home sets)
                val itHomeSets = homeSets.iterator()
                while (itHomeSets.hasNext()) {
                    val homeSetUrl = itHomeSets.next()
                    Logger.log.fine("Listing home set $homeSetUrl")

                    try {
                        DavResource(httpClient, homeSetUrl).propfind(1, *DAV_COLLECTION_PROPERTIES) { response, _ ->
                            if (!response.isSuccess())
                                return@propfind

                            val info = Collection.fromDavResponse(response) ?: return@propfind
                            info.confirmed = true
                            Logger.log.log(Level.FINE, "Found collection", info)

                            if ((service.type == Service.TYPE_CARDDAV && info.type == Collection.TYPE_ADDRESSBOOK) ||
                                (service.type == Service.TYPE_CALDAV && arrayOf(Collection.TYPE_CALENDAR, Collection.TYPE_WEBCAL).contains(info.type)))
                                collections[response.href] = info
                        }
                    } catch(e: HttpException) {
                        if (e.code in arrayOf(403, 404, 410))
                            // delete home set only if it was not accessible (40x)
                            itHomeSets.remove()
                    }
                }

                // check/refresh unconfirmed collections
                val itCollections = collections.entries.iterator()
                while (itCollections.hasNext()) {
                    val (url, info) = itCollections.next()
                    if (!info.confirmed)
                        try {
                            DavResource(httpClient, url).propfind(0, *DAV_COLLECTION_PROPERTIES) { response, _ ->
                                if (!response.isSuccess())
                                    return@propfind

                                val collection = Collection.fromDavResponse(response) ?: return@propfind
                                collection.confirmed = true

                                // remove unusable collections
                                if ((service.type == Service.TYPE_CARDDAV && collection.type != Collection.TYPE_ADDRESSBOOK) ||
                                    (service.type == Service.TYPE_CALDAV && !arrayOf(Collection.TYPE_CALENDAR, Collection.TYPE_WEBCAL).contains(collection.type)) ||
                                    (collection.type == Collection.TYPE_WEBCAL && collection.source == null))
                                    itCollections.remove()
                            }
                        } catch(e: HttpException) {
                            if (e.code in arrayOf(403, 404, 410))
                            // delete collection only if it was not accessible (40x)
                                itCollections.remove()
                            else
                                throw e
                        }
                }

                // restore selections
                for (url in selectedCollections)
                    collections[url]?.let { it.sync = true }
                for (url in deselectedCollections)
                    collections[url]?.let { it.sync = false }
            }

            saveResults()

        } catch(e: InvalidAccountException) {
            Logger.log.log(Level.SEVERE, "Invalid account", e)
        } catch(e: Exception) {
            Logger.log.log(Level.SEVERE, "Couldn't refresh collection list", e)

            val debugIntent = Intent(this, DebugInfoActivity::class.java)
            debugIntent.putExtra(DebugInfoActivity.KEY_THROWABLE, e)
            debugIntent.putExtra(DebugInfoActivity.KEY_ACCOUNT, account)

            val notify = NotificationUtils.newBuilder(this, NotificationUtils.CHANNEL_GENERAL)
                    .setSmallIcon(R.drawable.ic_sync_problem_notify)
                    .setContentTitle(getString(R.string.dav_service_refresh_failed))
                    .setContentText(getString(R.string.dav_service_refresh_couldnt_refresh))
                    .setContentIntent(PendingIntent.getActivity(this, 0, debugIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                    .setSubText(account.name)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build()
            NotificationManagerCompat.from(this)
                    .notify(serviceId.toString(), NotificationUtils.NOTIFY_REFRESH_COLLECTIONS, notify)
        } finally {
            runningRefresh.remove(serviceId)
            refreshingStatusListeners.mapNotNull { it.get() }.forEach {
                it.onDavRefreshStatusChanged(serviceId, false)
            }
        }

    }

}