package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar

class UsbListFragment : Fragment() {
    private lateinit var adapter: DeviceAdapter
    private lateinit var settings: Settings
    private lateinit var noUsbDeviceTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: MaterialToolbar

    private val mainViewModel: MainViewModel by activityViewModels()

    // A QDLink head unit is a USB host, so it never shows up in the device list;
    // the phone is its accessory. Poll for it while this screen is visible.
    private val qdLinkHandler = Handler(Looper.getMainLooper())
    private val qdLinkRefresh = object : Runnable {
        override fun run() {
            refreshQdLink()
            qdLinkHandler.postDelayed(this, 1000)
        }
    }

    private fun refreshQdLink() {
        val context = context ?: return
        val bridge = App.provide(context).qdLinkBridge
        val accessory = bridge.usbAccessory()
        val row = accessory?.let {
            QdLinkRow(listOfNotNull(it.manufacturer, it.model, it.version).joinToString(" "),
                bridge.isUsbConnected)
        }
        if (adapter.setQdLink(row)) {
            AppLog.i("USB menu: QDLink head unit row -> $row")
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        val empty = adapter.itemCount == 0
        noUsbDeviceTextView.visibility = if (empty) VISIBLE else GONE
        recyclerView.visibility = if (empty) GONE else VISIBLE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        recyclerView = view.findViewById(android.R.id.list)
        noUsbDeviceTextView = view.findViewById(R.id.no_usb_device_text)
        toolbar = view.findViewById(R.id.toolbar)

        settings = Settings(requireContext())
        adapter = DeviceAdapter(requireContext(), settings)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        
        // Add padding
        val padding = resources.getDimensionPixelSize(R.dimen.list_padding)
        recyclerView.setPadding(padding, padding, padding, padding)
        recyclerView.clipToPadding = false

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.title = getString(R.string.usb)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        mainViewModel.usbDevices.observe(viewLifecycleOwner, Observer {
            val allowDevices = settings.allowedDevices
            adapter.setData(it, allowDevices)
            updateEmptyState()
        })
    }

    override fun onResume() {
        super.onResume()
        qdLinkHandler.post(qdLinkRefresh)
    }

    override fun onPause() {
        qdLinkHandler.removeCallbacks(qdLinkRefresh)
        super.onPause()
        settings.commit()
    }

    private data class QdLinkRow(val name: String, val connected: Boolean)

    private companion object {
        /** View tag of the QDLink head unit row; device rows use their list index. */
        const val QDLINK_ROW = -1
    }

    private class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val allowButton = itemView.findViewById<Button>(android.R.id.button1)
        val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class DeviceAdapter(private val mContext: Context, private val mSettings: Settings) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {
        private var allowedDevices: MutableSet<String> = mutableSetOf()
        private var deviceList: List<UsbDeviceCompat> = listOf()
        private var qdLink: QdLinkRow? = null
        private var lastClickTime: Long = 0

        /** Rows ahead of the USB devices: the QDLink head unit, when one is attached. */
        private val headerCount: Int
            get() = if (qdLink != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(mContext).inflate(R.layout.list_item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            // Background styling logic
            val isTop = position == 0
            val isBottom = position == itemCount - 1
            val bgRes = when {
                isTop && isBottom -> R.drawable.bg_setting_single
                isTop -> R.drawable.bg_setting_top
                isBottom -> R.drawable.bg_setting_bottom
                else -> R.drawable.bg_setting_middle
            }
            holder.itemView.setBackgroundResource(bgRes)

            val headUnit = qdLink
            if (headUnit != null && position == 0) {
                holder.startButton.text = mContext.getString(R.string.qdlink_usb_head_unit, headUnit.name)
                holder.startButton.tag = QDLINK_ROW
                holder.startButton.setOnClickListener(this)
                holder.allowButton.setText(
                    if (headUnit.connected) R.string.connected else R.string.qdlink_usb_connect)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext,
                    if (headUnit.connected) R.color.material_green_700 else R.color.material_orange_700))
                holder.allowButton.tag = QDLINK_ROW
                holder.allowButton.isEnabled = !headUnit.connected
                holder.allowButton.setOnClickListener(this)
                return
            }

            val devicePosition = position - headerCount
            val device = deviceList[devicePosition]
            holder.startButton.text = device.uniqueName
            holder.startButton.tag = devicePosition
            holder.startButton.setOnClickListener(this)

            if (device.isInAccessoryMode) {
                holder.allowButton.setText(R.string.allowed)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                holder.allowButton.isEnabled = false
            } else {
                if (allowedDevices.contains(device.uniqueName)) {
                    holder.allowButton.setText(R.string.allowed)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                } else {
                    holder.allowButton.setText(R.string.ignored)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_orange_700))
                }
                holder.allowButton.tag = devicePosition
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            }
        }

        override fun getItemCount(): Int {
            return headerCount + deviceList.size
        }

        override fun onClick(v: View) {
            // Debounce clicks (prevent double tap)
            if (SystemClock.elapsedRealtime() - lastClickTime < 1000) {
                return
            }
            lastClickTime = SystemClock.elapsedRealtime()

            val position = v.tag as? Int ?: return
            if (position == QDLINK_ROW) {
                connectQdLink()
                return
            }
            if (position < 0 || position >= deviceList.size) {
                return
            }
            val device = deviceList[position]
            if (v.id == android.R.id.button1) {
                if (allowedDevices.contains(device.uniqueName)) {
                    allowedDevices.remove(device.uniqueName)
                } else {
                    allowedDevices.add(device.uniqueName)
                }
                mSettings.allowedDevices = allowedDevices
                notifyDataSetChanged()
            } else {
                if (App.provide(mContext).commManager.isConnected) {

                    // Already connected -> bring existing projection to front
                    val aapIntent = AapProjectionActivity.intent(mContext).apply {
                        putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    mContext.startActivity(aapIntent)
                } else if (device.isInAccessoryMode) {
                    // Device is in Accessory Mode but we are NOT connected.
                    // Start connection immediately.
                    Toast.makeText(mContext, R.string.android_auto_starting, Toast.LENGTH_SHORT).show()
                    (mContext as? MainActivity)?.beginAutoConnect(
                        "manual USB list (accessory mode)",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                    ContextCompat.startForegroundService(mContext, Intent(mContext, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
                } else {
                    // Standard connection flow
                    val usbManager = mContext.getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(device.wrappedDevice)) {
                        val usbMode = UsbAccessoryMode(usbManager)
                        if (usbMode.connectAndSwitch(device.wrappedDevice, mSettings.useLibusb)) {
                            Toast.makeText(mContext, R.string.switching_to_android_auto, Toast.LENGTH_SHORT).show()
                            (mContext as? MainActivity)?.beginAutoConnect(
                                "manual USB list (AOA switch)",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                        } else {
                            Toast.makeText(mContext, R.string.switch_failed, Toast.LENGTH_SHORT).show()
                        }
                        notifyDataSetChanged()
                    } else {
                        Toast.makeText(mContext, R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(mContext, Intent(mContext, AapService::class.java))
                        usbManager.requestPermission(
                            device.wrappedDevice,
                            UsbReceiver.createPermissionPendingIntent(mContext)
                        )
                    }
                }
            }
        }

        private fun connectQdLink() {
            val app = App.provide(mContext)
            val accessory = app.qdLinkBridge.usbAccessory()
            val usbManager = mContext.getSystemService(Context.USB_SERVICE) as UsbManager
            AppLog.i("USB menu: QDLink head unit tapped; accessory=${accessory != null} " +
                "permission=${accessory?.let { usbManager.hasPermission(it) }} " +
                "output=${app.settings.videoOutputMode} aaConnected=${app.commManager.isConnected} " +
                app.qdLinkBridge.describeState())
            if (accessory == null) return
            if (app.settings.videoOutputMode == Settings.VideoOutputMode.LOCAL) {
                Toast.makeText(mContext, R.string.qdlink_usb_needs_output, Toast.LENGTH_LONG).show()
            }
            if (usbManager.hasPermission(accessory)) {
                Toast.makeText(mContext, R.string.qdlink_usb_connecting, Toast.LENGTH_SHORT).show()
                ContextCompat.startForegroundService(mContext, Intent(mContext, AapService::class.java).apply {
                    action = AapService.ACTION_QDLINK_USB
                })
            } else {
                Toast.makeText(mContext, R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                usbManager.requestPermission(accessory, app.qdLinkBridge.usbPermissionIntent())
            }
        }

        /** @return true when the head unit row appeared, disappeared or changed state. */
        fun setQdLink(row: QdLinkRow?): Boolean {
            if (row == qdLink) return false
            qdLink = row
            notifyDataSetChanged()
            return true
        }

        fun setData(deviceList: List<UsbDeviceCompat>, allowedDevices: Set<String>) {
            this.allowedDevices = allowedDevices.toMutableSet()
            this.deviceList = deviceList
            notifyDataSetChanged()
        }
    }

}
