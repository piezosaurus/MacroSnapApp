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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import androidx.fragment.app.Fragment
import smile.classification.OneVersusRest
import smile.classification.ovr
import smile.classification.svm
import java.util.*

import java.io.*
import smile.*
import smile.data.*
import smile.classification.*
import smile.math.kernel.*
import smile.validation.*
import smile.math.MathEx.*
import smile.math.kernel.GaussianKernel
import smile.validation.metric.Accuracy


data class Dataset(val x: Array<DoubleArray>, val y: IntArray)

enum class GestureType(val str: String) {
    NONE("None"),
    FIST("Fist"),
    WRIST_SUPINATE("Wrist Supination"),
    WRIST_PRONATE("Wrist Pronation"),
    WRIST_UP("Wrist Up (Extension)"),
    WRIST_DOWN("Wrist Down (Flexion)"),
}

class ScannerFragment : Fragment() {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var spinner1: Spinner
    private lateinit var alarmText: TextView
    private lateinit var alarmTimePicker: TimePicker
    private lateinit var alarmButton: Button
    private lateinit var spinner2: Spinner
    private lateinit var timerText: TextView
    private lateinit var timerTimePicker: MyTimePicker
    private lateinit var timerButton: Button
    private lateinit var spinner3: Spinner
    private lateinit var debugTextView: TextView

    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var btScanner: BluetoothLeScanner? = null
    private val scanRestartPeriod: Long = 600000  // restart scan every 10 mins (600000 millisec)

    // Gesture tasks
    private var gestureSelection1: Int = 0
    private var alarmHour: Int = 9
    private var alarmMinute: Int = 0
    private var gestureSelection2: Int = 0
    private var timerHour: Int = 0
    private var timerMinute: Int = 0
    private var timerSecond: Int = 0
    private var gestureSelection3: Int = 0

    // Calibration
    private var prevCalib: Int = 0

    // FSR raw values (range 0 to 255)
    // 0 is no force, 255 is high force
    private var needRestValue: Boolean = true
    private val arraySize: Int = 10
    private var dataIndex: Int = 0
    private val fsrRestValue = doubleArrayOf(0.0, 0.0, 0.0)
    private val fsr1Data = mutableListOf<Double>()
    private val fsr2Data = mutableListOf<Double>()
    private val fsr3Data = mutableListOf<Double>()

    // Store training data (min 10 samples per gesture)
    private val datasetSize: Int = 50
    private var datasetIndex: Int = 0
    private val datasetX = mutableListOf<DoubleArray>()
    private val datasetY = mutableListOf<Int>()
    private var prevGestureLabel: Int = 0
    private var currGestureCount: Int = 0

    // SVM model
    private var model: OneVersusRest<DoubleArray>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scanner, container, false)
        initViews(view)
        setUpBluetoothManager()
        loadSVM()
        // max continuous scan time is 30 min
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                restartScanner()
            }
        }, scanRestartPeriod, scanRestartPeriod)
        return view
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val PERMISSION_REQUEST_COARSE_LOCATION = 1
    }

    private fun initViews(view: View) {
        debugTextView = view.findViewById(R.id.debug_status)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        startButton.setOnClickListener { onStartScannerButtonClick() }
        stopButton.setOnClickListener { onStopScannerButtonClick() }
        // Task 1
        spinner1 = view.findViewById(R.id.spinner1)
        spinner1.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection1 = position
                Log.e("MACROSNAP","Task 1 gesture $gestureSelection1")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection1 = 0
            }
        })
        activity?.let {
            ArrayAdapter.createFromResource(
                it,
                R.array.gesture_types,
                android.R.layout.simple_spinner_item
            ).also { adapter ->
                // Specify the layout to use when the list of choices appears
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                // Apply the adapter to the spinner
                spinner1.adapter = adapter
            }
        }
        alarmText = view.findViewById(R.id.alarm_time)
        alarmButton = view.findViewById(R.id.alarmButton)
        alarmTimePicker = view.findViewById(R.id.alarmTimePicker)
        alarmTimePicker.setOnTimeChangedListener { _, hour, minute -> var hour = hour
            alarmHour = hour
            alarmMinute = minute
            var am_pm = ""
            // AM_PM decider logic
            when {hour == 0 -> { hour += 12
                am_pm = "AM"
            }
                hour == 12 -> am_pm = "PM"
                hour > 12 -> { hour -= 12
                    am_pm = "PM"
                }
                else -> am_pm = "AM"
            }
            if (alarmText != null) {
                val hour = if (hour < 10) "0" + hour else hour
                val min = if (minute < 10) "0" + minute else minute
                // display format of time
                val msg = "$hour:$min $am_pm"
                alarmText.text = msg
            }
        }
        alarmButton.setOnClickListener { onAlarmButtonClick() }
        // Task 2
        spinner2 = view.findViewById(R.id.spinner2)
        spinner2.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection2 = position
                Log.e("MACROSNAP","Task 2 gesture $gestureSelection2")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection1 = 0
            }
        })
        activity?.let {
            ArrayAdapter.createFromResource(
                it,
                R.array.gesture_types,
                android.R.layout.simple_spinner_item
            ).also { adapter ->
                // Specify the layout to use when the list of choices appears
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                // Apply the adapter to the spinner
                spinner2.adapter = adapter
            }
        }
        timerText = view.findViewById(R.id.timer_time)
        timerButton = view.findViewById(R.id.timerButton)
        timerTimePicker = view.findViewById(R.id.timerTimePicker)
        timerTimePicker.setOnTimeChangedListener(
            object: MyTimePicker.OnTimeChangedListener {
                override fun onTimeChanged(var1: MyTimePicker?, var2: Int, var3: Int, var4: Int) {
                    timerHour = var2
                    timerMinute = var3
                    timerSecond = var4
                    timerText.text = "%02d:%02d:%02d".format(timerHour, timerMinute, timerSecond)
                }
            }
        )
        timerButton.setOnClickListener { onTimerButtonClick() }
        // Task 3
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
            Log.e("MACROSNAP","Restarted scan")
        }
    }

    private fun onAlarmButtonClick() {
        if (alarmTimePicker.visibility == View.GONE) {
            alarmText.visibility = View.GONE
            alarmTimePicker.visibility = View.VISIBLE
            alarmButton.setText("Set time")
        }
        else {
            alarmText.visibility = View.VISIBLE
            alarmTimePicker.visibility = View.GONE
            alarmButton.setText("Change time")
        }
    }

    private fun onTimerButtonClick() {
        if (timerTimePicker.visibility == View.GONE) {
            timerText.visibility = View.GONE
            timerTimePicker.visibility = View.VISIBLE
            timerButton.setText("Set time")
        }
        else {
            timerText.visibility = View.VISIBLE
            timerTimePicker.visibility = View.GONE
            timerButton.setText("Change time")
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

    private fun writeData(x: Array<DoubleArray>, y: IntArray) {
        var fileOutputStream: FileOutputStream
        try {
            fileOutputStream = this.context?.openFileOutput("x.txt", Context.MODE_PRIVATE)!!
            val objectOutputStream = ObjectOutputStream(fileOutputStream)
            objectOutputStream.writeObject(x)
            objectOutputStream.close()
            Log.e("MACROSNAP","Write data success.")
        } catch (e: Exception){
            e.printStackTrace()
        }

        try {
            fileOutputStream = this.context?.openFileOutput("y.txt", Context.MODE_PRIVATE)!!
            val objectOutputStream = ObjectOutputStream(fileOutputStream)
            objectOutputStream.writeObject(y)
            objectOutputStream.close()
            Log.e("MACROSNAP","Write data success.")
        } catch (e: Exception){
            e.printStackTrace()
        }
    }

    private fun readData(): Dataset {
        var fileInputStream: FileInputStream? = null
        fileInputStream = this.context?.openFileInput("x.txt")
        var objectInputStream = ObjectInputStream(fileInputStream)
        val x: Array<DoubleArray> = objectInputStream.readObject() as Array<DoubleArray>
        objectInputStream.close()

        fileInputStream = this.context?.openFileInput("y.txt")
        objectInputStream = ObjectInputStream(fileInputStream)
        val y: IntArray = objectInputStream.readObject() as IntArray
        objectInputStream.close()

        return Dataset(x, y)
    }

    private fun loadSVM() {
        val xFile = File(this.context?.filesDir,"x.txt")
        val yFile = File(this.context?.filesDir,"y.txt")
        if(xFile.exists() && yFile.exists()){
            Log.e("MACROSNAP","Dataset files found.")
            debugTextView.text = "Found saved calibration"
            val (x, y) = readData()
            trainSVM(x, y)
            showNormalUI()
        } else {
            Log.e("MACROSNAP","Dataset files not found.")
            debugTextView.text = "Calibration required"
            // request calibration UI
            requestCalibrationUI()
        }
    }

    private fun preprocessData(): DoubleArray {
        // segment data
        val fsr1Filtered = mutableListOf<Double>()
        val fsr2Filtered = mutableListOf<Double>()
        val fsr3Filtered = mutableListOf<Double>()
        val fsrMax = doubleArrayOf(
            max(fsr1Data.toDoubleArray()),
            max(fsr2Data.toDoubleArray()),
            max(fsr3Data.toDoubleArray())
        )
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

    private fun trainSVM(x: Array<DoubleArray>, y: IntArray) {
        val kernel = GaussianKernel(1/6.0)  // gamma = 1/num_features
        model = ovr(x, y, { x, y -> svm(x, y, kernel, 1.0, 1E-3) })
//        var pred = CrossValidation.classification(10, x, y, {a, b -> ovr(a, b, { c, d -> svm(c, d, kernel, 5.0, 1E-3) })})
        val acc = Accuracy.of(y, model!!.predict(x))
        Log.e("MACROSNAP", "SVM acc: $acc")
//        println(Accuracy.of(y, model.predict(x)))
//        println(ConfusionMatrix.of(y, model.predict(x)))
        // save dataset
        val xFile = File(this.context?.filesDir,"x.txt")
        val yFile = File(this.context?.filesDir,"y.txt")
        if(!xFile.exists() || !yFile.exists()){
            writeData(x, y)
        }
    }

    private fun emptyData() {
        dataIndex = 0
        fsr1Data.clear()
        fsr2Data.clear()
        fsr3Data.clear()
    }

    private fun updateUI(status: Int) {
        if (status == prevCalib) {
            // no need to change UI
            return
        }
        if (status == 0) {
            showNormalUI()
        }
        else if (status > 0) {
            showCalibrationUI(status)
        }
        prevCalib = status
    }

    private fun showNormalUI() {
        // hide calibration UI
        // show normal UI elements
    }

    private fun requestCalibrationUI() {
        // function called when user does not have saved calibration data
        // display UI to ask user
    }

    private fun showCalibrationUI(status: Int) {
        // hide request calibration UI elements
        // hide normal UI elements
        // show calibration UI

        // determine what UI to show based on status which is the calib value
        // find the progress of each gesture based on currGestureCount
    }

    private fun runAppIntent(gesture: GestureType) {
        // run task based on given gesture
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

                    // only store raw FSR values if
                    // connected is 1 -> all modules of armband is wired
                    // status is 1 -> snap was detected
                    // needRestValue is true -> initial scans being received
                    if (connected.toInt() == 1  && (status.toInt() == 1 || needRestValue)) {
                        fsr1Data[dataIndex] = fsr1.toDouble() / 255.0 - fsrRestValue[0]
                        fsr2Data[dataIndex] = fsr2.toDouble() / 255.0 - fsrRestValue[1]
                        fsr3Data[dataIndex] = fsr3.toDouble() / 255.0 - fsrRestValue[2]
                        debugTextView.text = "FSR1 %.4f FSR2 %.4f FSR3 %.4f".format(fsr1Data[dataIndex], fsr2Data[dataIndex], fsr3Data[dataIndex])
                        dataIndex += 1
                    }

                    // store average scan value used for normalization
                    if (dataIndex >= arraySize && needRestValue) {
                        fsrRestValue[0] = mean(fsr1Data.toDoubleArray())
                        fsrRestValue[1] = mean(fsr2Data.toDoubleArray())
                        fsrRestValue[2] = mean(fsr3Data.toDoubleArray())
                        Log.e("MACROSNAP", "Average rest values " + fsrRestValue.joinToString())
                        needRestValue = false
                        // reset list
                        emptyData()
                    }

                    // run prediction
                    if (dataIndex >= arraySize && status.toInt() == 1 && calib.toInt() == 0) {
                        val fsrFeatures = preprocessData()
                        Log.e("MACROSNAP", "Processed features " + fsrFeatures.joinToString())
                        // run svm
                        val pred = model!!.predict(fsrFeatures)  // type int
                        val gesture = GestureType.values()[pred]
                        Log.e("MACROSNAP", "Predicted gesture " + gesture.str)
                        // run task
                        runAppIntent(gesture)
                        // reset list
                        emptyData()
                    }

                    // run calibration
                    if (dataIndex >= arraySize && calib.toInt() >= 2) {
                        val fsrFeatures = preprocessData()
                        Log.e("MACROSNAP", "Processed features " + fsrFeatures.joinToString())
                        val label = calib.toInt() - 2
                        datasetX.add(datasetIndex, fsrFeatures)
                        datasetY.add(datasetIndex, label)
                        datasetIndex += 1
                        if (prevGestureLabel != label) {
                            // if prev gesture is not the same, set the count to 1
                            currGestureCount = 1
                        }
                        else {
                            currGestureCount += 1
                        }
                    }

                    if (datasetIndex >= datasetSize && calib.toInt() == 9) {
                        // calibration data collection completed
                        trainSVM(datasetX.toTypedArray(), datasetY.toIntArray())
                    }

                    // update UI
                    updateUI(calib.toInt())
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MACROSNAP", errorCode.toString())
        }
    }

}