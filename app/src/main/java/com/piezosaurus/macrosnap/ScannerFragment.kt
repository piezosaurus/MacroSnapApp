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
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.fragment.app.Fragment
import kotlin.random.Random
import smile.*
import smile.classification.*
import smile.data.*
import smile.math.MathEx.*
import smile.math.kernel.*
import smile.validation.*
import smile.validation.metric.Accuracy
import smile.validation.metric.ConfusionMatrix
import java.io.*
import java.util.*


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
    private lateinit var viewSwitcher: ViewSwitcher
    private lateinit var spinner1: Spinner
    private lateinit var alarmText: TextView
    private lateinit var alarmTimePicker: TimePicker
    private lateinit var alarmButton: Button
    private lateinit var spinner2: Spinner
    private lateinit var timerText: TextView
    private lateinit var timerTimePicker: MyTimePicker
    private lateinit var timerButton: Button
    private lateinit var spinner3: Spinner
    private lateinit var addressText: TextView
    private lateinit var addressEditText: EditText
    private lateinit var addressButton: Button
    private lateinit var debugTextView: TextView

    // Calibration
    private lateinit var calibTitle: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var calibDescription: TextView
    private lateinit var calibImage: ImageView
    private lateinit var nextButton: Button  // delete later

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
    private var address: String = ""

    // Calibration
    private var prevCalib: Double = 0.0
    private var calibImageDrawables = arrayOf<String>()
    private var calibDescriptionText = arrayOf<String>()

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
    private var gesture1Accuracy = 0.0
    private var gesture2Accuracy = 0.0
    private var gesture3Accuracy = 0.0

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
        viewSwitcher = view.findViewById(R.id.viewSwitcher)
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
                gestureSelection2 = 0
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
        spinner3 = view.findViewById(R.id.spinner3)
        spinner3.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection3 = position
                Log.e("MACROSNAP","Task 3 gesture $gestureSelection3")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection3 = 0
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
                spinner3.adapter = adapter
            }
        }
        addressText = view.findViewById(R.id.address_text)
        addressEditText = view.findViewById(R.id.addressEditText)
        addressButton = view.findViewById(R.id.addressButton)
        addressButton.setOnClickListener { onAddressButtonClick() }
        // Calibration
        calibTitle = view.findViewById(R.id.calib_title)
        progressText = view.findViewById(R.id.gesture_progress)
        progressBar = view.findViewById(R.id.progressBar)
        calibDescription = view.findViewById(R.id.calib_description)
        calibImage = view.findViewById(R.id.calib_image)
        calibImageDrawables = resources.getStringArray(R.array.calib_images)
        calibDescriptionText = resources.getStringArray(R.array.calib_descriptions)
        // delete later
        nextButton = view.findViewById(R.id.nextUI)
        nextButton.setOnClickListener { onNextUIButtonClick() }
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

    private fun onAddressButtonClick() {
        if (addressEditText.visibility == View.GONE) {
            addressText.visibility = View.GONE
            addressEditText.visibility = View.VISIBLE
            addressButton.setText("Set address")
        }
        else {
            if (addressEditText.text.isNotEmpty()) {
                addressText.text = addressEditText.text.toString()
                address = addressEditText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            addressText.visibility = View.VISIBLE
            addressEditText.visibility = View.GONE
            addressButton.setText("Change address")
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
        // delete later
        viewSwitcher.showNext()
        prevCalib = 0.5


        val xFile = File(this.context?.filesDir,"x.txt")
        val yFile = File(this.context?.filesDir,"y.txt")
        if(xFile.exists() && yFile.exists()){
            Log.e("MACROSNAP","Dataset files found.")
            debugTextView.text = "Found saved calibration"
            val (x, y) = readData()
            trainSVM(x, y)
        } else {
            Log.e("MACROSNAP","Dataset files not found.")
            debugTextView.text = "Calibration required"
            // request calibration UI
            viewSwitcher.showNext()
            prevCalib = 0.5
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

    private fun crossValidation(X: Array<DoubleArray>, Y: IntArray): DoubleArray {
        // shuffle dataset
        val shuffleIndex = List(X.size) { Random.nextInt(0, X.size-1) }
        val shuffledX = X.slice(shuffleIndex)
        val shuffledY = Y.slice(shuffleIndex)
        // split dataset into 5 chunks
        val splitX = shuffledX.toList().chunked(5)
        val splitY = shuffledY.toList().chunked(5)
        // 5-fold cross validation
        val nClasses = 9  // change to 6 later
        val numCorrect = MutableList(nClasses) { 0 }
        val numTotal = MutableList(nClasses) { 0 }
        for (i in splitX.indices) {
            val testX = splitX[i]
            val testY = splitY[i]
            val trainX: MutableList<DoubleArray> = ArrayList()
            val trainY: MutableList<Int> = ArrayList()
            for (j in splitX.indices) {
                if (i != j) {
                    trainX += splitX[j]
                    trainY += splitY[j]
                }
            }
            val kernel = GaussianKernel(1/6.0)  // gamma = 1/num_features
            val model = ovr(trainX.toTypedArray(), trainY.toIntArray(), { x, y -> svm(x, y, kernel, 1.0, 1E-3) })
            val confMatrix = ConfusionMatrix.of(testY.toIntArray(), model.predict(testX.toTypedArray())).matrix
            for (k in confMatrix.indices) {
                numCorrect[k] += confMatrix[k][k]
                numTotal[k] += sum(confMatrix[k]).toInt()
            }
        }
        val accuracy = MutableList(nClasses) { 0.0 }
        for (i in numCorrect.indices) {
            if (numTotal[i] > 0) {
                accuracy[i] = numCorrect[i].toDouble() / numTotal[i]
            }
        }
        return accuracy.toDoubleArray()
    }

    private fun trainSVM(x: Array<DoubleArray>, y: IntArray) {
        // determine top 3 gestures based on cross validation accuracy
        val classAccuracies = crossValidation(x, y)
        val meanAcc = mean(classAccuracies)
        Log.e("MACROSNAP", "SVM acc: $meanAcc")
        val indices = arrayOf(1, 2, 3, 4, 5)
        val sortedIndices = indices.sortedByDescending { classAccuracies[it] }
        gestureSelection1 = sortedIndices[0]
        gestureSelection2 = sortedIndices[1]
        gestureSelection3 = sortedIndices[2]
        gesture1Accuracy = classAccuracies[gestureSelection1]
        gesture2Accuracy = classAccuracies[gestureSelection2]
        gesture3Accuracy = classAccuracies[gestureSelection3]
        Log.e("MACROSNAP", "Task 1 gesture $gestureSelection1 acc: %.4f".format(gesture1Accuracy))
        Log.e("MACROSNAP", "Task 2 gesture $gestureSelection2 acc: %.4f".format(gesture2Accuracy))
        Log.e("MACROSNAP", "Task 3 gesture $gestureSelection3 acc: %.4f".format(gesture3Accuracy))
        // train on full dataset
        val kernel = GaussianKernel(1/6.0)  // gamma = 1/num_features
        model = ovr(x, y, { x, y -> svm(x, y, kernel, 1.0, 1E-3) })
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
        Log.e("MACROSNAP", "Updating UI status: $status")
        if (status == 1 && prevCalib == 0.0) {
            viewSwitcher.showNext()  // switch from normal to calibration UI
        } else if (status == 0 && prevCalib >= 7.0) {
            viewSwitcher.showPrevious()  // switch from calibration to normal UI
        }
        if (status > 0) {
            setCalibrationUI(status)
        }
        prevCalib = status.toDouble()
    }

    private fun onNextUIButtonClick() { // delete later
        Log.e("MACROSNAP", "$prevCalib $currGestureCount")
        var status = prevCalib.toInt()
        if (prevCalib == 0.5) {
            status = 1
        }
        else if (prevCalib <= 1.0 || (prevCalib >= 1.0 && currGestureCount >= 5)) {
            status = (prevCalib + 1).toInt()
            currGestureCount = 0
        }
        else if (prevCalib >= 1.0 && prevCalib < 7.0) {
            currGestureCount += 1
        }
        else if (prevCalib == 7.0) {
            status = 0
        }
        updateUI(status)
    }

    private fun setCalibrationUI(status: Int) {
        Log.e("MACROSNAP", "Set Calibration UI $status")
        if (status == 1) {
            calibTitle.text = "Snap Calibration"
        }
        else if (status < 7) {
            val gestureText = GestureType.values()[status-1].str
            calibTitle.text = "Gesture Calibration: $gestureText"
        }

        if (status == 7) {
            calibTitle.text = "Calibration Complete!"
            val gesture1Text = GestureType.values()[gestureSelection1].str
            val gesture2Text = GestureType.values()[gestureSelection2].str
            val gesture3Text = GestureType.values()[gestureSelection3].str
            calibDescription.text = "Recommended gestures and accuracy: \n  " + gesture1Text + " (set to Task 1) %.2f \n  ".format(gesture1Accuracy*100) + gesture2Text + " (set to Task 2) %.2f \n  ".format(gesture1Accuracy*100) + gesture3Text + " (set to Task 3) %.2f ".format(gesture3Accuracy) + "\nClick 'START SCANNING' to start using Macro Snap!"
            calibImage.setImageResource(android.R.color.transparent)
            onStopScannerButtonClick()
        }
        else {
            val imageId = resources.getIdentifier(calibImageDrawables[status-1], "drawable", activity?.packageName)
            calibImage.setImageResource(imageId)
            calibDescription.text = calibDescriptionText[status-1]
        }

        if (status in 2..6) {
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE
            progressText.text = "$currGestureCount/5"
            if (currGestureCount >= 1) {
                progressBar.incrementProgressBy(20)
            }
            else {
                progressBar.progress = 0
            }
        }
        else {
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE
        }
    }

    private fun runAppIntent(gestureIndex: Int) {
        // run task based on given gesture
        when (gestureIndex) {
            gestureSelection1 -> {
                // run task 1
                return
            }
            gestureSelection2 -> {
                // run task 2
                return
            }
            gestureSelection3 -> {
                // run task 3
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
                    debugTextView.text = "vbat $battery, fsrs [$fsr1, $fsr2, $fsr3], calib $calib, status $status, connected $connected"

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
                        runAppIntent(pred)
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

                    if (datasetIndex >= datasetSize && calib.toInt() == 7) {
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