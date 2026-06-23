package com.aura.di

import com.aura.transport.FrameInbox
import com.aura.transport.TcpMessageServer
import com.aura.transport.rtc.PeerTransport
import com.aura.transport.rtc.RtcTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the data-plane boundary: WebRTC delivery + the TCP server's inbound processor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {
    @Binds
    @Singleton
    abstract fun bindPeerTransport(impl: RtcTransport): PeerTransport

    @Binds
    @Singleton
    abstract fun bindFrameInbox(impl: TcpMessageServer): FrameInbox
}
