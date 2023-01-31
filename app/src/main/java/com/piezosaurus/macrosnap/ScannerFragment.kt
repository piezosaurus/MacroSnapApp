package com.piezosaurus.macrosnap

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import smile.classification.OneVersusRest
import java.util.*

import smile.math.MathEx.*


data class Dataset(val x: Array<DoubleArray>, val y: IntArray)

enum class GestureType(val str: String) {
    FIST("Fist"),
    WRIST_SUPINATE("Wrist Supination"),
    WRIST_PRONATE("Wrist Pronation"),
    WRIST_UP("Wrist Up (Extension)"),
    WRIST_DOWN("Wrist Down (Flexion)"),
}

class ScannerFragment : Fragment() {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var btScanner: BluetoothLeScanner? = null

    // FSR raw values (range 0 to 255)
    // 0 is no force, 255 is high force
    private val arraySize: Int = 10
    private var dataIndex: Int = 0
    private val fsrRestValue = intArrayOf(-1, -1, -1)
    private val fsr1Data = DoubleArray(arraySize)
    private val fsr2Data = DoubleArray(arraySize)
    private val fsr3Data = DoubleArray(arraySize)

    // Store training data
    private val datasetSize: Int = 10
    private val datasetIndex: Int = 0
    private val datasetX = Array<DoubleArray>(datasetSize){doubleArrayOf(0.0)}
    private val datasetY = IntArray(datasetSize)

    // SVM model
    private val model: OneVersusRest<Double>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scanner, container, false)
        initViews(view)
        setUpBluetoothManager()
        return view
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val PERMISSION_REQUEST_COARSE_LOCATION = 1
    }

    private fun initViews(view: View) {
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        startButton.setOnClickListener { onStartScannerButtonClick() }
        stopButton.setOnClickListener { onStopScannerButtonClick() }
    }

    private fun onStartScannerButtonClick() {
        startButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        val names = arrayOf("Panda", "Grizz", "Ice Bear")
        val filters: MutableList<ScanFilter> = ArrayList()
        for (name in names) {
            val filter = ScanFilter.Builder()
                .setDeviceName(name)
                .build()
            filters.add(filter)
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()
        btScanner!!.startScan(filters, scanSettings, leScanCallback)
    }

    private fun onStopScannerButtonClick() {
        stopButton.visibility = View.GONE
        startButton.visibility = View.VISIBLE
        btScanner!!.stopScan(leScanCallback)
    }

    private fun restartScanner() {
        if (startButton.visibility == View.GONE) {
            btScanner!!.stopScan(leScanCallback)
            onStartScannerButtonClick()
        }
    }

    private fun setUpBluetoothManager() {
        btManager = activity?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager!!.adapter
        btScanner = btAdapter?.bluetoothLeScanner
        if (btAdapter != null && !btAdapter!!.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
        }
        checkForLocationPermission()
    }

    private fun checkForLocationPermission() {
        // Make sure we have access coarse location enabled, if not, prompt the user to enable it
        if (activity!!.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("This app needs location access")
            builder.setMessage("Please grant location access so this app can detect  peripherals.")
            builder.setPositiveButton(android.R.string.ok, null)
            builder.setOnDismissListener {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    PERMISSION_REQUEST_COARSE_LOCATION
                )
            }
            builder.show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST_COARSE_LOCATION -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    println("coarse location permission granted")
                } else {
                    val builder = AlertDialog.Builder(activity)
                    builder.setTitle("Functionality limited")
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover BLE beacons")
                    builder.setPositiveButton(android.R.string.ok, null)
                    builder.setOnDismissListener { }
                    builder.show()
                }
                return
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord
            if (scanRecord != null) {
                val deviceName = scanRecord.deviceName
                val serviceData = scanRecord.serviceData.values
                val scanData = serviceData.elementAtOrNull(0)
                if (scanData != null) {
                    val battery = scanData[0].toUByte().toUInt()
                    val fsr1 = scanData[1].toUByte().toUInt()
                    val fsr2 = scanData[2].toUByte().toUInt()
                    val fsr3 = scanData[3].toUByte().toUInt()
                    val calib = scanData[4].toUByte().toUInt()
                    val status = scanData[5].toUByte().toUInt()
                    val connected = scanData[6].toUByte().toUInt()
                    Log.e("MACROSNAP", deviceName)
                    Log.e(
                        "MACROSNAP",
                        "vbat $battery, fsrs [$fsr1, $fsr2, $fsr3], calib $calib, status $status, connected $connected"
                    )

                    // Code for testing
                    fsr1Data[dataIndex] = (fsr1.toInt() - fsrRestValue[0]).toDouble() / 255.0
                    fsr2Data[dataIndex] = (fsr2.toInt() - fsrRestValue[1]).toDouble() / 255.0
                    fsr3Data[dataIndex] = (fsr3.toInt() - fsrRestValue[2]).toDouble() / 255.0
                    dataIndex += 1
                    if (dataIndex >= arraySize) {
                        val fsrFeatures = preprocessData()
                        Log.e("MACROSNAP", "Processed features " + fsrFeatures.joinToString())
                        dataIndex = 0
                    }

                    // Actual pipeline logic
                    /*
                    if (fsrRestValue[0] == -1 && connected.toInt() == 1) {
                        // first scan value used for normalization
                        fsrRestValue[0] = fsr1.toInt()
                        fsrRestValue[1] = fsr2.toInt()
                        fsrRestValue[2] = fsr3.toInt()
                    }
                    else if (fsrRestValue[0] != -1) {
                        fsr1Data[dataIndex] = (fsr1.toInt() - fsrRestValue[0]).toDouble() / 255.0
                        fsr2Data[dataIndex] = (fsr2.toInt() - fsrRestValue[1]).toDouble() / 255.0
                        fsr3Data[dataIndex] = (fsr3.toInt() - fsrRestValue[2]).toDouble() / 255.0
                        dataIndex += 1
                        Log.e("MACROSNAP", "Processed features $fsr1Data, $fsr2Data, $fsr3Data")
                        if (dataIndex >= arraySize) {
                            val fsrFeatures = preprocessData()
                            Log.e("MACROSNAP", "Processed features $fsrFeatures")
                            dataIndex = 0
                        }
                    }
                     */
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MACROSNAP", errorCode.toString())
        }

        private fun preprocessData(): DoubleArray {
            // segment data
            val fsr1Filtered = mutableListOf<Double>()
            val fsr2Filtered = mutableListOf<Double>()
            val fsr3Filtered = mutableListOf<Double>()
            val fsrMax = doubleArrayOf(max(fsr1Data), max(fsr2Data), max(fsr3Data))
            val whichFsrMax = whichMax(fsrMax)
            val filterThreshold = 0.4 * fsrMax[whichFsrMax]
            val filterFsrTarget = when (whichFsrMax) {
                1 -> {
                    fsr1Data
                }
                2 -> {
                    fsr2Data
                }
                else -> {
                    fsr3Data
                }
            }
            for (i in filterFsrTarget.indices) {
                if (filterFsrTarget[i] >= filterThreshold) {
                    fsr1Filtered.add(fsr1Data[i])
                    fsr2Filtered.add(fsr2Data[i])
                    fsr3Filtered.add(fsr3Data[i])
                }
            }
            // calculate mean and std
            return doubleArrayOf(
                mean(fsr1Filtered.toDoubleArray()),
                mean(fsr2Filtered.toDoubleArray()),
                mean(fsr3Filtered.toDoubleArray()),
                sd(fsr1Filtered.toDoubleArray()),
                sd(fsr2Filtered.toDoubleArray()),
                sd(fsr3Filtered.toDoubleArray())
            )
        }
    }

}