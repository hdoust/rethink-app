/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemWgPeersBinding
import com.celzero.bravedns.service.WireGuardManager
import com.celzero.bravedns.service.WireGuardManager.WARP_ID
import com.celzero.bravedns.ui.WgAddPeerDialog
import com.celzero.bravedns.wireguard.Peer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgPeersAdapter(
    val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val themeId: Int,
    private val configId: Int,
    private var peers: MutableList<Peer>
) : RecyclerView.Adapter<WgPeersAdapter.WgPeersViewHolder>() {

    override fun onBindViewHolder(holder: WgPeersViewHolder, position: Int) {
        val peer: Peer = peers[position]
        holder.update(peer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WgPeersViewHolder {
        val itemBinding =
            ListItemWgPeersBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WgPeersViewHolder(itemBinding)
    }

    override fun getItemCount(): Int {
        return peers.size
    }

    inner class WgPeersViewHolder(private val b: ListItemWgPeersBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(wgPeer: Peer) {
            if (configId == WARP_ID) {
                handleWarpPeers()
            }
            if (wgPeer.getEndpoint().isPresent) {
                b.endpointText.text = wgPeer.getEndpoint().get().toString()
            } else {
                b.endpointText.visibility = View.GONE
                b.endpointLabel.visibility = View.GONE
            }
            if (wgPeer.getAllowedIps().isNotEmpty()) {
                b.allowedIpsText.text = wgPeer.getAllowedIps().joinToString { it.toString() }
            } else {
                b.allowedIpsText.visibility = View.GONE
                b.allowedIpsLabel.visibility = View.GONE
            }
            if (wgPeer.persistentKeepalive.isPresent) {
                b.persistentKeepaliveText.text = wgPeer.persistentKeepalive.get().toString()
            } else {
                b.persistentKeepaliveText.visibility = View.GONE
                b.persistentKeepaliveLabel.visibility = View.GONE
            }
            if (wgPeer.getPreSharedKey().isPresent) {
                b.preSharedKeyText.text = wgPeer.getPreSharedKey().get().base64()
            } else {
                b.preSharedKeyText.visibility = View.GONE
                b.preSharedKeyLabel.visibility = View.GONE
            }
            b.publicKeyText.text = wgPeer.getPublicKey().base64()

            b.peerEdit.setOnClickListener { openEditPeerDialog(wgPeer) }
            b.peerDelete.setOnClickListener { showDeleteInterfaceDialog(wgPeer) }
        }

        private fun handleWarpPeers() {
            b.peerEdit.visibility = View.GONE
            b.peerDelete.visibility = View.GONE
        }
    }

    private fun openEditPeerDialog(wgPeer: Peer) {
        // send 0 as peerId to indicate that it is a new peer
        val addPeerDialog = WgAddPeerDialog(context as Activity, themeId, configId, wgPeer)
        addPeerDialog.setCanceledOnTouchOutside(false)
        addPeerDialog.show()
        addPeerDialog.setOnDismissListener { dataChanged() }
    }

    fun dataChanged() {
        peers.clear()
        peers.addAll(WireGuardManager.getPeers(configId))
        this?.notifyDataSetChanged()
    }

    private fun showDeleteInterfaceDialog(wgPeer: Peer) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle("Delete?")
        builder.setMessage("Are you sure you want to delete this Config?")
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
            deletePeer(wgPeer)
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun deletePeer(wgPeer: Peer) {
        ui {
            ioCtx {
                WireGuardManager.deletePeer(configId, wgPeer)
                peers = WireGuardManager.getPeers(configId)
            }
            this.notifyDataSetChanged()
        }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
