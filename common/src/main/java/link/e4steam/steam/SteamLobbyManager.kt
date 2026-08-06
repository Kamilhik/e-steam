package link.e4steam.steam

import com.codedisaster.steamworks.SteamAPICall
import com.codedisaster.steamworks.SteamFriends
import com.codedisaster.steamworks.SteamFriendsCallback
import com.codedisaster.steamworks.SteamID
import com.codedisaster.steamworks.SteamMatchmaking
import com.codedisaster.steamworks.SteamMatchmakingCallback
import com.codedisaster.steamworks.SteamNativeHandle
import com.codedisaster.steamworks.SteamResult
import link.e4steam.E4steamClient
import link.e4steam.MinecraftVersion
import link.e4steam.Mirror
import java.io.IOException
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import java.util.function.LongConsumer

/** Steam lobby, friends, overlay and invite state. Called only by the Steam worker. */
class SteamLobbyManager(private val runtime: SteamRuntime) : AutoCloseable {
    private val minecraftVersion = MinecraftVersion.current()
    private lateinit var friends: SteamFriends
    private lateinit var matchmaking: SteamMatchmaking

    init {
        friends = SteamFriends(object : SteamFriendsCallback {
        override fun onGameLobbyJoinRequested(lobby: SteamID, friend: SteamID) {
            requestJoin(lobby, friend)
        }

        override fun onGameRichPresenceJoinRequested(friend: SteamID, connect: String?) {
            if (connect == null) {
                return
            }
            val friendId = SteamNativeHandle.getNativeHandle(friend)
            val direct = SteamAddress.tryParse(connect)
            if (direct.isPresent
                    && direct.get().steamId() == friendId
                    && friends.getFriendRelationship(friend) == SteamFriends.FriendRelationship.Friend) {
                E4steamClient.acceptSteamInvite(connect, friends.getFriendPersonaName(friend))
                return
            }
            if (!connect.startsWith(LOBBY_CONNECT_PREFIX)) {
                return
            }
            try {
                val lobbyId = java.lang.Long.parseUnsignedLong(connect.substring(LOBBY_CONNECT_PREFIX.length))
                requestJoin(SteamID.createFromNativeHandle(lobbyId), friend)
            } catch (_: NumberFormatException) {
                E4steamClient.LOGGER.debug("Ignored an invalid Steam rich-presence join string")
            }
        }
        })
        matchmaking = SteamMatchmaking(object : SteamMatchmakingCallback {
        override fun onLobbyCreated(result: SteamResult, lobby: SteamID) {
            handleLobbyCreated(result, lobby)
        }

        override fun onLobbyEnter(
                lobby: SteamID,
                chatPermissions: Int,
                blocked: Boolean,
                response: SteamMatchmaking.ChatRoomEnterResponse
        ) {
            handleLobbyEnter(lobby, response)
        }

        override fun onLobbyDataUpdate(lobby: SteamID, member: SteamID, success: Boolean) {
            val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
            if (success && guestLobbyId == lobbyId && guestEndpoint == null) {
                resolveGuestEndpoint()
            }
        }

        override fun onLobbyChatUpdate(
                lobby: SteamID,
                changedUser: SteamID,
                makingChange: SteamID,
                stateChange: SteamMatchmaking.ChatMemberStateChange
        ) {
            if (stateChange == SteamMatchmaking.ChatMemberStateChange.Entered) {
                return
            }
            val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
            val userId = SteamNativeHandle.getNativeHandle(changedUser)
            if (hostLobbyOwner != null && hostLobbyId == lobbyId) {
                if (userId == runtime.steamIdValue()) {
                    loseHostLobby("Steam removed the host from its lobby")
                    return
                }
                runtime.closeRemoteBridges(userId)
            }
            if (guestLobbyId == lobbyId
                    && (guestHostSteamId == userId || userId == runtime.steamIdValue())) {
                loseGuestLobby()
            }
        }

        override fun onLobbyKicked(lobby: SteamID, admin: SteamID, disconnected: Boolean) {
            val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
            if (hostLobbyOwner != null && hostLobbyId == lobbyId) {
                loseHostLobby("Steam closed the host lobby")
            } else if (guestLobbyId == lobbyId) {
                loseGuestLobby()
            }
        }
        })
    }

    private var pendingHostOwner: SteamSession? = null
    private var pendingHostAccessMode: SteamAccessMode? = null
    private var pendingHostAddress: SteamAddress? = null
    private var pendingHostResult: CompletableFuture<Long>? = null
    private var pendingHostAttempts = 0
    private var pendingHostCanceled = false
    private var queuedHostOwner: SteamSession? = null
    private var queuedHostAccessMode: SteamAccessMode? = null
    private var queuedHostAddress: SteamAddress? = null
    private var queuedHostResult: CompletableFuture<Long>? = null
    private var hostLobbyOwner: SteamSession? = null
    private var hostLobbyAccessMode: SteamAccessMode? = null
    private var hostLobbyId = 0L
    private var guestLobbyId = 0L
    private var guestHostSteamId = 0L
    private var guestInviterSteamId = 0L
    private var guestJoinState: SteamGuestJoinState? = null
    private var guestEndpoint: String? = null
    private var requestedLobbyId = 0L
    private var requestedFriendId = 0L
    private var requestedJoinDeadlineMillis = 0L
    private val canceledJoinLobbyIds: MutableSet<Long> = HashSet()

    fun createHostLobby(
            owner: SteamSession,
            accessMode: SteamAccessMode,
            address: SteamAddress
    ): CompletableFuture<Long> {
        val result = CompletableFuture<Long>()
        if (accessMode == SteamAccessMode.LOCAL_ONLY) {
            result.completeExceptionally(IOException("Local-only mode does not create a Steam lobby"))
            return result
        }
        if (hostLobbyOwner != null || queuedHostOwner != null) {
            result.completeExceptionally(IOException("A Steam lobby is already active"))
            return result
        }

        if (pendingHostOwner != null) {
            if (!pendingHostCanceled) {
                result.completeExceptionally(IOException("A Steam lobby is already being created"))
            } else {
                queuedHostOwner = owner
                queuedHostAccessMode = accessMode
                queuedHostAddress = address
                queuedHostResult = result
            }
            return result
        }

        issueHostCreate(owner, accessMode, address, result, 0)
        return result
    }

    fun stopHosting(owner: SteamSession) {
        if (pendingHostOwner == owner) {
            // Steam's lobby-created callback does not identify its API call.
            // Keep this canceled request as a tombstone so a late callback
            // cannot be mistaken for a newer hosting session.
            pendingHostCanceled = true
            pendingHostResult!!.completeExceptionally(IOException("Steam hosting was stopped"))
        }
        if (queuedHostOwner == owner) {
            val result = queuedHostResult
            clearQueuedHost()
            result!!.completeExceptionally(IOException("Steam hosting was stopped"))
        }
        if (hostLobbyOwner == owner) {
            if (hostLobbyId != 0L) {
                val lobby = SteamID.createFromNativeHandle(hostLobbyId)
                matchmaking.setLobbyJoinable(lobby, false)
                matchmaking.leaveLobby(lobby)
            }
            clearHostLobby()
            friends.clearRichPresence()
        }
    }

    @Throws(IOException::class)
    fun openHostInviteOverlay(owner: SteamSession) {
        if (hostLobbyOwner != owner) {
            throw IOException("Steam lobby is not ready")
        }
        requireOverlay()
        if (hostLobbyId == 0L) {
            openFriendsOverlayCompat()
            return
        }
        friends.activateGameOverlayInviteDialog(SteamID.createFromNativeHandle(hostLobbyId))
    }

    @Throws(IOException::class)
    fun openFriendsOverlay() {
        requireOverlay()
        openFriendsOverlayCompat()
    }

    fun allows(owner: SteamSession, remoteSteamId: Long): Boolean {
        if (hostLobbyOwner != owner) {
            return false
        }
        return isAllowedHostPeer(remoteSteamId)
    }

    fun mayAcceptPeer(remoteSteamId: Long): Boolean {
        if (guestLobbyId != 0L && guestHostSteamId == remoteSteamId) {
            return true
        }
        return hostLobbyOwner != null && isAllowedHostPeer(remoteSteamId)
    }

    fun forEachKnownSessionPeer(consumer: LongConsumer) {
        if (guestLobbyId != 0L && guestHostSteamId != 0L) {
            consumer.accept(guestHostSteamId)
        }

        if (hostLobbyOwner == null || hostLobbyId == 0L) {
            return
        }
        val lobbyId = SteamID.createFromNativeHandle(hostLobbyId)
        val memberCount = matchmaking.getNumLobbyMembers(lobbyId)
        for (index in 0 until memberCount) {
            val member = matchmaking.getLobbyMemberByIndex(lobbyId, index)
            if (member == null) {
                continue
            }
            val remoteSteamId = SteamNativeHandle.getNativeHandle(member)
            if (remoteSteamId != 0L
                    && remoteSteamId != runtime.steamIdValue()
                    && friends.getFriendRelationship(member) == SteamFriends.FriendRelationship.Friend) {
                consumer.accept(remoteSteamId)
            }
        }
    }

    fun keepsRuntimeAlive(): Boolean {
        return (pendingHostOwner != null && !pendingHostCanceled)
                || queuedHostOwner != null
                || hostLobbyOwner != null
                || guestLobbyId != 0L
                || requestedLobbyId != 0L
    }

    fun clientBridgeOpened(remoteSteamId: Long) {
        if (guestLobbyId != 0L && guestHostSteamId == remoteSteamId) {
            guestJoinState!!.connected()
        }
    }

    fun clientBridgeClosed(remoteSteamId: Long, anotherBridgeExists: Boolean) {
        if (guestLobbyId != 0L && guestHostSteamId == remoteSteamId && !anotherBridgeExists) {
            leaveGuestLobby()
        }
    }

    fun cancelGuestJoin() {
        leaveGuestLobby()
    }

    fun claimGuestInvite(endpoint: String?): Boolean {
        if (guestLobbyId == 0L
                || endpoint == null
                || !endpoint.equals(guestEndpoint)
                || !guestJoinState!!.claim()) {
            return false
        }
        return true
    }

    fun beginGuestConnect(endpoint: String?): Boolean {
        if (guestLobbyId != 0L
                && endpoint != null
                && endpoint.equals(guestEndpoint)) {
            return guestJoinState!!.beginConnect(
                    System.currentTimeMillis() + GUEST_JOIN_TIMEOUT_MILLIS
            )
        }
        return false
    }

    fun cleanup(now: Long) {
        if (requestedLobbyId != 0L && requestedJoinDeadlineMillis <= now) {
            leaveGuestLobby()
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinLobbyTimeout"))
            return
        }
        if (guestLobbyId != 0L && guestJoinState!!.expired(now)) {
            if (runtime.hasClientBridgeForRemote(guestHostSteamId)) {
                guestJoinState!!.connected()
                return
            }
            leaveGuestLobby()
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinConnectTimeout"))
        }
    }

    private fun handleLobbyCreated(result: SteamResult, lobby: SteamID) {
        val owner = pendingHostOwner
        val accessMode = pendingHostAccessMode
        val address = pendingHostAddress
        val hostResult = pendingHostResult
        val attempts = pendingHostAttempts
        val canceled = pendingHostCanceled
        clearPendingHost()
        val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
        if (owner == null) {
            if (lobbyId != 0L) {
                matchmaking.leaveLobby(lobby)
            }
            return
        }
        if (canceled) {
            if (lobbyId != 0L) {
                matchmaking.leaveLobby(lobby)
            }
            issueQueuedHostCreate()
            return
        }
        if (shouldRetryHostCreation(result, attempts)) {
            E4steamClient.LOGGER.warn(
                    "Steam could not create the lobby ({}); retrying ({}/{})",
                    result,
                    attempts + 1,
                    HOST_LOBBY_MAX_ATTEMPTS
            )
            issueHostCreate(owner, accessMode!!, address!!, hostResult!!, attempts)
            return
        }
        if (result != SteamResult.OK || lobbyId == 0L) {
            if (!hostResult!!.isDone) {
                hostResult.completeExceptionally(IOException("Steam lobby creation failed: $result"))
            } else {
                E4steamClient.LOGGER.warn(
                        "Steam lobby creation failed with {}; continuing friends-only sharing by address",
                        result
                )
            }
            issueQueuedHostCreate()
            return
        }

        val metadataReady = matchmaking.setLobbyJoinable(lobby, false)
                && matchmaking.setLobbyData(lobby, KEY_PROTOCOL, PROTOCOL_VERSION)
                && matchmaking.setLobbyData(lobby, KEY_MINECRAFT, minecraftVersion)
                && matchmaking.setLobbyData(lobby, KEY_ENDPOINT, address!!.inviteString())
                && matchmaking.setLobbyJoinable(lobby, true)
        if (!metadataReady) {
            matchmaking.leaveLobby(lobby)
            if (!hostResult!!.isDone) {
                hostResult.completeExceptionally(IOException("Steam rejected e4steam lobby metadata"))
            } else {
                E4steamClient.LOGGER.warn(
                        "Steam rejected lobby metadata; continuing friends-only sharing by address"
                )
            }
            issueQueuedHostCreate()
            return
        }

        hostLobbyOwner = owner
        hostLobbyAccessMode = accessMode
        hostLobbyId = lobbyId
        friends.clearRichPresence()
        friends.setRichPresence("status", "Hosting a Minecraft LAN world")
        if (accessMode == SteamAccessMode.FRIENDS_ONLY) {
            friends.setRichPresence("connect", LOBBY_CONNECT_PREFIX + java.lang.Long.toUnsignedString(lobbyId))
        }
        hostResult!!.complete(lobbyId)
    }

    private fun issueHostCreate(
            owner: SteamSession,
            accessMode: SteamAccessMode,
            address: SteamAddress,
            result: CompletableFuture<Long>,
            completedAttempts: Int
    ) {
        pendingHostOwner = owner
        pendingHostAccessMode = accessMode
        pendingHostAddress = address
        pendingHostResult = result
        pendingHostAttempts = completedAttempts + 1
        pendingHostCanceled = false
        var call: Long
        try {
            call = createLobbyCompat(
                    matchmaking,
                    accessMode == SteamAccessMode.FRIENDS_ONLY,
                    VANILLA_LOBBY_CAPACITY
            )
        } catch (exception: ReflectiveOperationException) {
            clearPendingHost()
            result.completeExceptionally(IOException("Steam lobby compatibility call failed", exception))
            issueQueuedHostCreate()
            return
        }
        if (call == 0L) {
            clearPendingHost()
            result.completeExceptionally(IOException("Steam rejected the lobby creation request"))
            issueQueuedHostCreate()
        }
    }

    private fun issueQueuedHostCreate() {
        if (pendingHostOwner != null || hostLobbyOwner != null || queuedHostOwner == null) {
            return
        }
        val owner = queuedHostOwner
        val accessMode = queuedHostAccessMode
        val address = queuedHostAddress
        val result = queuedHostResult
        clearQueuedHost()
        issueHostCreate(owner!!, accessMode!!, address!!, result!!, 0)
    }

    private fun clearPendingHost() {
        pendingHostOwner = null
        pendingHostAccessMode = null
        pendingHostAddress = null
        pendingHostResult = null
        pendingHostAttempts = 0
        pendingHostCanceled = false
    }

    private fun clearQueuedHost() {
        queuedHostOwner = null
        queuedHostAccessMode = null
        queuedHostAddress = null
        queuedHostResult = null
    }

    private fun clearHostLobby() {
        hostLobbyOwner = null
        hostLobbyAccessMode = null
        hostLobbyId = 0
    }

    private fun clearGuestLobby() {
        guestLobbyId = 0
        guestHostSteamId = 0
        guestInviterSteamId = 0
        guestJoinState = null
        guestEndpoint = null
    }

    @Throws(ReflectiveOperationException::class)
    private fun createLobbyCompat(
            matchmaking: SteamMatchmaking,
            friendsOnly: Boolean,
            capacity: Int
    ): Long {
        val steamInterface = matchmaking.javaClass.superclass
        val callback = steamInterface.getDeclaredField("callback")
        callback.isAccessible = true
        val callbackHandle = callback.getLong(matchmaking)

        val nativeType = Class.forName("com.codedisaster.steamworks.SteamMatchmakingNative")
        val createLobby = nativeType.getDeclaredMethod(
                "createLobby",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
        )
        createLobby.isAccessible = true
        // Steamworks lobby type ordinals: Private=0, FriendsOnly=1.
        return createLobby.invoke(null, callbackHandle, if (friendsOnly) 1 else 0, capacity) as Long
    }

    @Throws(IOException::class)
    private fun openFriendsOverlayCompat() {
        try {
            val nativeType = Class.forName("com.codedisaster.steamworks.SteamFriendsNative")
            val activate = nativeType.getDeclaredMethod("activateGameOverlay", String::class.java)
            activate.isAccessible = true
            activate.invoke(null, "Friends")
        } catch (exception: ReflectiveOperationException) {
            throw IOException("Steam friends overlay compatibility call failed", exception)
        }
    }

    private fun requestJoin(lobby: SteamID, friend: SteamID) {
        val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
        if (lobbyId == 0L || (hostLobbyOwner != null && hostLobbyId == lobbyId)) {
            return
        }
        if (canceledJoinLobbyIds.contains(lobbyId)) {
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinCancelPending"))
            return
        }
        if (requestedLobbyId == lobbyId || guestLobbyId == lobbyId) {
            return
        }
        if (guestLobbyId != 0L
                && (guestEndpoint != null
                || guestJoinState!!.isConnected()
                || runtime.hasClientBridgeForRemote(guestHostSteamId))) {
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinCurrentSession"))
            return
        }
        if (guestLobbyId != 0L || requestedLobbyId != 0L) {
            leaveGuestLobby()
        }
        requestedLobbyId = lobbyId
        requestedFriendId = SteamNativeHandle.getNativeHandle(friend)
        requestedJoinDeadlineMillis = System.currentTimeMillis() + GUEST_JOIN_TIMEOUT_MILLIS
        val call = matchmaking.joinLobby(lobby)
        if (call == null || !call.isValid()) {
            requestedLobbyId = 0
            requestedFriendId = 0
            requestedJoinDeadlineMillis = 0
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinRejected"))
        }
    }

    private fun handleLobbyEnter(lobby: SteamID, response: SteamMatchmaking.ChatRoomEnterResponse) {
        val lobbyId = SteamNativeHandle.getNativeHandle(lobby)
        if (canceledJoinLobbyIds.remove(lobbyId)) {
            matchmaking.leaveLobby(lobby)
            return
        }
        if (hostLobbyOwner != null && hostLobbyId == lobbyId) {
            return
        }
        if (requestedLobbyId != lobbyId) {
            matchmaking.leaveLobby(lobby)
            return
        }
        requestedLobbyId = 0
        requestedJoinDeadlineMillis = 0
        if (response != SteamMatchmaking.ChatRoomEnterResponse.Success) {
            requestedFriendId = 0
            E4steamClient.showSteamJoinFailure(Mirror.translatable(
                    "text.e4steam_minecraft.joinLobbyEnterFailed",
                    response
            ))
            return
        }

        val owner = matchmaking.getLobbyOwner(lobby)
        val ownerId = if (owner == null) 0 else SteamNativeHandle.getNativeHandle(owner)
        if (ownerId == 0L
                || ownerId == runtime.steamIdValue()
                || friends.getFriendRelationship(owner) != SteamFriends.FriendRelationship.Friend) {
            matchmaking.leaveLobby(lobby)
            requestedFriendId = 0
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinOwnerNotFriend"))
            return
        }
        guestLobbyId = lobbyId
        guestHostSteamId = ownerId
        guestInviterSteamId = requestedFriendId
        guestJoinState = SteamGuestJoinState(System.currentTimeMillis() + GUEST_JOIN_TIMEOUT_MILLIS)
        guestEndpoint = null
        requestedFriendId = 0
        resolveGuestEndpoint()
    }

    private fun resolveGuestEndpoint() {
        if (guestLobbyId == 0L || guestEndpoint != null) {
            return
        }
        val lobby = SteamID.createFromNativeHandle(guestLobbyId)
        val protocol = matchmaking.getLobbyData(lobby, KEY_PROTOCOL)
        val minecraft = matchmaking.getLobbyData(lobby, KEY_MINECRAFT)
        val endpoint = matchmaking.getLobbyData(lobby, KEY_ENDPOINT)
        if (protocol == null || protocol.isBlank() || endpoint == null || endpoint.isBlank()) {
            matchmaking.requestLobbyData(lobby)
            return
        }
        if (!PROTOCOL_VERSION.equals(protocol) || !minecraftVersion.equals(minecraft)) {
            leaveGuestLobby()
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinIncompatible"))
            return
        }
        val parsed = SteamAddress.tryParse(endpoint)
        if (parsed.isEmpty() || parsed.get().steamId() != guestHostSteamId) {
            leaveGuestLobby()
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinInvalidAddress"))
            return
        }

        guestEndpoint = endpoint
        // The in-world confirmation has no countdown. Keep this lobby alive
        // until the user chooses Join, then beginGuestConnect() starts the
        // bounded Minecraft connection window.
        guestJoinState!!.waitForConfirmation()
        val hostName = friends.getFriendPersonaName(SteamID.createFromNativeHandle(guestHostSteamId))
        E4steamClient.acceptSteamInvite(endpoint, hostName)
    }

    @Throws(IOException::class)
    private fun requireOverlay() {
        if (!runtime.isOverlayEnabledOnWorker()) {
            throw IOException(
                    "Steam Overlay is unavailable. Add your Minecraft launcher to Steam and launch it from Steam first"
            )
        }
    }

    private fun isAllowedHostPeer(remoteSteamId: Long): Boolean {
        val remote = SteamID.createFromNativeHandle(remoteSteamId)
        if (friends.getFriendRelationship(remote) != SteamFriends.FriendRelationship.Friend) {
            return false
        }

        // Friends-only deliberately permits the copied address as a fallback.
        // A private lobby is stricter: membership proves that Steam admitted
        // this friend through an invitation for the current hosting session.
        if (hostLobbyAccessMode == SteamAccessMode.FRIENDS_ONLY) {
            return true
        }

        val lobbyId = SteamID.createFromNativeHandle(hostLobbyId)
        val memberCount = matchmaking.getNumLobbyMembers(lobbyId)
        for (index in 0 until memberCount) {
            val member = matchmaking.getLobbyMemberByIndex(lobbyId, index)
            if (member != null && SteamNativeHandle.getNativeHandle(member) == remoteSteamId) {
                return true
            }
        }
        return false
    }

    private fun loseHostLobby(detail: String) {
        val lostOwner = hostLobbyOwner
        if (lostOwner == null) {
            return
        }
        clearHostLobby()
        friends.clearRichPresence()
        lostOwner.runtimeFailed(IOException(detail))
    }

    private fun loseGuestLobby() {
        if (guestLobbyId == 0L) {
            return
        }
        guestJoinState!!.loseLobby()
        runtime.closeRemoteBridges(guestHostSteamId)
        leaveGuestLobby()
        E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinLobbyClosed"))
    }

    private fun leaveGuestLobby() {
        val currentLobbyId = guestLobbyId
        val currentJoinState = guestJoinState
        val requested = requestedLobbyId
        clearGuestLobby()
        requestedLobbyId = 0
        requestedFriendId = 0
        requestedJoinDeadlineMillis = 0
        if (currentLobbyId != 0L) {
            currentJoinState!!.cancel()
            matchmaking.leaveLobby(SteamID.createFromNativeHandle(currentLobbyId))
        } else if (requested != 0L) {
            // LobbyEnter does not identify the JoinLobby API call. Preserve a
            // tombstone so a late callback cannot be accepted as a later
            // retry for the same lobby in this Steam runtime generation.
            canceledJoinLobbyIds.add(requested)
            matchmaking.leaveLobby(SteamID.createFromNativeHandle(requested))
        }
    }

    override fun close() {
        val pendingResult = pendingHostResult
        val hadPendingHost = pendingHostOwner != null
        clearPendingHost()
        if (hadPendingHost) {
            pendingResult!!.completeExceptionally(IOException("Steam runtime stopped before creating the lobby"))
        }
        val queuedResult = queuedHostResult
        val hadQueuedHost = queuedHostOwner != null
        clearQueuedHost()
        if (hadQueuedHost) {
            queuedResult!!.completeExceptionally(IOException("Steam runtime stopped before creating the lobby"))
        }
        if (hostLobbyOwner != null) {
            if (hostLobbyId != 0L) {
                val lobby = SteamID.createFromNativeHandle(hostLobbyId)
                matchmaking.setLobbyJoinable(lobby, false)
                matchmaking.leaveLobby(lobby)
            }
            clearHostLobby()
        }
        leaveGuestLobby()
        canceledJoinLobbyIds.clear()
        friends.clearRichPresence()
        matchmaking.dispose()
        friends.dispose()
    }

    companion object {
        const val VANILLA_LOBBY_CAPACITY = 8
        const val VANILLA_MAX_GUESTS = VANILLA_LOBBY_CAPACITY - 1
        const val HOST_LOBBY_MAX_ATTEMPTS = 6

        private const val KEY_PROTOCOL = "e4steam_protocol"
        private const val KEY_MINECRAFT = "e4steam_minecraft"
        private const val KEY_ENDPOINT = "e4steam_endpoint"
        private val PROTOCOL_VERSION = SteamProtocol.VERSION.toString()
        private const val LOBBY_CONNECT_PREFIX = "e4steam-lobby:"
        private const val GUEST_JOIN_TIMEOUT_MILLIS = 30_000L

        @JvmStatic
        fun shouldRetryHostCreation(result: SteamResult, completedAttempts: Int): Boolean {
            val temporaryNetworkFailure = result == SteamResult.Timeout
                    || result == SteamResult.NoConnection
                    || result == SteamResult.ServiceUnavailable
                    || result == SteamResult.Busy
            return temporaryNetworkFailure && completedAttempts < HOST_LOBBY_MAX_ATTEMPTS
        }

        @JvmStatic
        fun canStartBeforeLobby(accessMode: SteamAccessMode): Boolean {
            return false
        }
    }
}
