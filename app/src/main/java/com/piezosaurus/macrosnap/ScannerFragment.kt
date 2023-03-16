package com.piezosaurus.macrosnap

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_scanner.*
import smile.*
import smile.classification.*
import smile.data.*
import smile.math.MathEx.*
import smile.math.kernel.*
import smile.validation.*
import smile.validation.metric.ConfusionMatrix
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.random.Random


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
    private lateinit var debugTextView: TextView
    private lateinit var graph1: GraphView
    private lateinit var graph2: GraphView
    private lateinit var graph3: GraphView
    private lateinit var spinnerDevice: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewSwitcher: ViewSwitcher
    private lateinit var spinner1: Spinner
    private lateinit var addressText: TextView
    private lateinit var addressEditText: EditText
    private lateinit var addressButton: Button
    private lateinit var spinner2: Spinner
    private lateinit var timerText: TextView
    private lateinit var timerTimePicker: MyTimePicker
    private lateinit var timerButton: Button
    private lateinit var spinner3: Spinner
    private lateinit var playlistLinkText: TextView
    private lateinit var playlistLinkEditText: EditText
    private lateinit var playlistLinkButton: Button
    private lateinit var spinner4: Spinner
    private lateinit var spotifyButton: Button
    private lateinit var spinner5: Spinner
    private lateinit var startRecordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var playRecordButton: Button
    private lateinit var stopPlayRecordButton: Button
    private lateinit var spinner6: Spinner
    private lateinit var websiteText: TextView
    private lateinit var websiteEditText: EditText
    private lateinit var websiteButton: Button
    private lateinit var spinner7: Spinner
    private lateinit var phoneText: TextView
    private lateinit var phoneEditText: EditText
    private lateinit var phoneButton: Button
    private lateinit var spinner8: Spinner
    private lateinit var noteMsgText: TextView
    private lateinit var noteEditText: EditText
    private lateinit var noteButton: Button
    private lateinit var noteText: TextView
    private lateinit var clearNoteButton: Button

    // Calibration
    private lateinit var calibTitle: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var calibDescription: TextView
    private lateinit var calibImage: ImageView

    private var prevCalib: Int = 8
    private var calibImageDrawables = arrayOf<String>()
    private var calibDescriptionText = arrayOf<String>()

    // BLE
    private var bleDeviceSelection = 0
    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var btScanner: BluetoothLeScanner? = null
    private val scanRestartPeriod: Long = 600000  // restart scan every 10 mins (600000 millisec)

    // Gesture tasks
    private var gestureSelection1: Int = 0
    private var address: String = ""
    private var gestureSelection2: Int = 0
    private var timerHour: Int = 0
    private var timerMinute: Int = 0
    private var timerSecond: Int = 30
    private var gestureSelection3: Int = 0
    private var playlistLink: String = ""
    private var gestureSelection4: Int = 0
    private var gestureSelection5: Int = 0
    private var saveAudioPath: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var gestureSelection6: Int = 0
    private var websiteUrl: String = ""
    private var gestureSelection7: Int = 0
    private var phoneNumber: String = ""
    private var gestureSelection8: Int = 0
    private var noteMsg: String = ""

    // FSR raw values (range 0 to 255)
    // 0 is no force, 255 is high force
    private var needRestValue: Boolean = true
    private val arraySize: Int = 20
    private var dataIndex: Int = 0
    private val fsrRestValue = doubleArrayOf(0.0, 0.0, 0.0)
    private val fsr1Data = mutableListOf<Double>()
    private val fsr2Data = mutableListOf<Double>()
    private val fsr3Data = mutableListOf<Double>()
    private var prevGestureStatus: Int = 0
    private var cancelGesture: Boolean = false

    // Store training data
    private val datasetX = mutableListOf<DoubleArray>()
    private val datasetY = mutableListOf<Int>()
    private var prevCalibStatus: Int = 0
    private var currGestureCount: Int = 0

    // KNN model
    private var knn: KNN<DoubleArray>? = null
    private var gesture1Accuracy = 0.0
    private var gesture2Accuracy = 0.0
    private var gesture3Accuracy = 0.0

    // Tasks
    private var tasks: Tasks? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scanner, container, false)
        initViews(view)
        tasks = Tasks(context!!, activity!!)
        setUpBluetoothManager()
        loadModel()
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
        private const val PERMISSION_REQUEST_FINE_LOCATION = 1
    }

    private fun initViews(view: View) {
        spinnerDevice = view.findViewById(R.id.spinnerDevice)
        spinnerDevice.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                bleDeviceSelection = position
                restartScanner()
                Log.e("MACROSNAP","BLE device changed $bleDeviceSelection")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                bleDeviceSelection = 0
            }
        })
        activity?.let {
            ArrayAdapter.createFromResource(
                it,
                R.array.armband_names,
                android.R.layout.simple_spinner_item
            ).also { adapter ->
                // Specify the layout to use when the list of choices appears
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                // Apply the adapter to the spinner
                spinnerDevice.adapter = adapter
            }
        }
        graph1 = view.findViewById(R.id.graph1)
        graph2 = view.findViewById(R.id.graph2)
        graph3 = view.findViewById(R.id.graph3)
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
        addressText = view.findViewById(R.id.address_text)
        addressEditText = view.findViewById(R.id.addressEditText)
        addressButton = view.findViewById(R.id.addressButton)
        addressButton.setOnClickListener { onAddressButtonClick() }
        address = addressText.text.toString()
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
        playlistLinkText = view.findViewById(R.id.link_text)
        playlistLinkEditText = view.findViewById(R.id.linkEditText)
        playlistLinkButton = view.findViewById(R.id.linkButton)
        playlistLinkButton.setOnClickListener { onPlaylistLinkButtonClick() }
        playlistLink = playlistLinkText.text.toString()
        // Task 4
        spinner4 = view.findViewById(R.id.spinner4)
        spinner4.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection4 = position
                Log.e("MACROSNAP","Task 4 gesture $gestureSelection4")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection4 = 0
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
                spinner4.adapter = adapter
            }
        }
        spotifyButton = view.findViewById(R.id.spotifyButton)
        spotifyButton.setOnClickListener { onSpotifyButtonClick() }
        // Task 5
        spinner5 = view.findViewById(R.id.spinner5)
        spinner5.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection5 = position
                Log.e("MACROSNAP","Task 5 gesture $gestureSelection5")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection5 = 0
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
                spinner5.adapter = adapter
            }
        }
        startRecordButton = view.findViewById(R.id.startRecordButton)
        stopRecordButton = view.findViewById(R.id.stopRecordButton)
        playRecordButton = view.findViewById(R.id.playRecordButton)
        stopPlayRecordButton = view.findViewById(R.id.stopPlayRecordButton)
        startRecordButton.setOnClickListener {
            if (activity!!.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && activity!!.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                activity!!.requestPermissions(permissions, 0)
            } else {
                startRecording()
            }
        }
        stopRecordButton.setOnClickListener{ stopRecording() }
        playRecordButton.setOnClickListener { playRecording() }
        stopPlayRecordButton.setOnClickListener{ stopPlayRecording() }
        // Task 6
        spinner6 = view.findViewById(R.id.spinner6)
        spinner6.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection6 = position
                Log.e("MACROSNAP","Task 6 gesture $gestureSelection6")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection6 = 0
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
                spinner6.adapter = adapter
            }
        }
        websiteText = view.findViewById(R.id.website_text)
        websiteEditText = view.findViewById(R.id.websiteEditText)
        websiteButton = view.findViewById(R.id.websiteButton)
        websiteButton.setOnClickListener { onWebsiteButtonClick() }
        websiteUrl = websiteText.text.toString()
        // Task 7
        spinner7 = view.findViewById(R.id.spinner7)
        spinner7.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection7 = position
                Log.e("MACROSNAP","Task 7 gesture $gestureSelection7")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection7 = 0
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
                spinner7.adapter = adapter
            }
        }
        phoneText = view.findViewById(R.id.phone_number_text)
        phoneEditText = view.findViewById(R.id.phoneEditText)
        phoneButton = view.findViewById(R.id.phoneButton)
        phoneButton.setOnClickListener { onPhoneButtonClick() }
        phoneNumber = phoneText.text.toString()
        // Task 8
        spinner8 = view.findViewById(R.id.spinner8)
        spinner8.onItemSelectedListener = (object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gestureSelection8 = position
                Log.e("MACROSNAP","Task 8 gesture $gestureSelection8")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                gestureSelection8 = 0
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
                spinner8.adapter = adapter
            }
        }
        noteMsgText = view.findViewById(R.id.noteMsg)
        noteEditText = view.findViewById(R.id.noteEditText)
        noteText = view.findViewById(R.id.notes)
        noteButton = view.findViewById(R.id.noteButton)
        noteButton.setOnClickListener { onNoteButtonClick() }
        clearNoteButton= view.findViewById(R.id.clearNoteButton)
        clearNoteButton.setOnClickListener { onClearNoteButtonClick() }
        noteMsg = noteMsgText.text.toString()
        // Calibration
        calibTitle = view.findViewById(R.id.calib_title)
        progressText = view.findViewById(R.id.gesture_progress)
        progressBar = view.findViewById(R.id.progressBar)
        calibDescription = view.findViewById(R.id.calib_description)
        calibImage = view.findViewById(R.id.calib_image)
        calibImageDrawables = resources.getStringArray(R.array.calib_images)
        calibDescriptionText = resources.getStringArray(R.array.calib_descriptions)
    }

    private fun onStartScannerButtonClick() {
        startButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        val deviceName = resources.getStringArray(R.array.armband_names)[bleDeviceSelection]
        val names = arrayOf(deviceName)
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

    private fun onAddressButtonClick() {
        if (addressEditText.visibility == View.GONE) {
            addressText.visibility = View.GONE
            addressEditText.visibility = View.VISIBLE
            addressButton.setText("Set location")
        }
        else {
            if (addressEditText.text.isNotEmpty()) {
                addressText.text = addressEditText.text.toString()
                address = addressEditText.text.toString()
            }
            else {
                address = addressText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            addressText.visibility = View.VISIBLE
            addressEditText.visibility = View.GONE
            addressButton.setText("Change location")
        }
    }

    private fun onSpotifyButtonClick() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = ComponentName("com.spotify.music", "com.spotify.music.MainActivity")
        context?.startActivity(intent)
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

    private fun onPlaylistLinkButtonClick() {
        if (playlistLinkEditText.visibility == View.GONE) {
            playlistLinkText.visibility = View.GONE
            playlistLinkEditText.visibility = View.VISIBLE
            playlistLinkButton.setText("Save playlist")
        }
        else {
            if (playlistLinkEditText.text.isNotEmpty()) {
                playlistLinkText.text = playlistLinkEditText.text.toString()
                playlistLink = playlistLinkEditText.text.toString()
            }
            else {
                playlistLink = playlistLinkText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            playlistLinkText.visibility = View.VISIBLE
            playlistLinkEditText.visibility = View.GONE
            playlistLinkButton.setText("Change playlist")
        }
    }

    private fun startRecording() {
        try {
            mediaRecorder = MediaRecorder()
            saveAudioPath = Environment.getExternalStorageDirectory().absolutePath + "/recording.mp3"
            mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder?.setOutputFile(saveAudioPath)
            mediaRecorder?.prepare()
            mediaRecorder?.start()
            Toast.makeText(activity, "Recording started!", Toast.LENGTH_SHORT).show()
            startRecordButton.visibility = View.GONE
            stopRecordButton.visibility = View.VISIBLE
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        mediaRecorder?.stop()
        mediaRecorder?.release()
        startRecordButton.visibility = View.VISIBLE
        stopRecordButton.visibility = View.GONE
    }

    private fun playRecording() {
        if (saveAudioPath == "") {
            Toast.makeText(activity, "No recordings saved!", Toast.LENGTH_SHORT).show()
            return
        }
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setDataSource(saveAudioPath)
        mediaPlayer?.prepare()
        mediaPlayer?.start()
        mediaPlayer?.setOnCompletionListener{ stopPlayRecording() }
        playRecordButton.visibility = View.GONE
        stopPlayRecordButton.visibility = View.VISIBLE
    }

    private fun stopPlayRecording() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
        }
        mediaPlayer?.release()
        playRecordButton.visibility = View.VISIBLE
        stopPlayRecordButton.visibility = View.GONE
    }

    private fun onWebsiteButtonClick() {
        if (websiteEditText.visibility == View.GONE) {
            websiteText.visibility = View.GONE
            websiteEditText.visibility = View.VISIBLE
            websiteButton.setText("Save website")
        }
        else {
            if (websiteEditText.text.isNotEmpty()) {
                val url = websiteEditText.text.toString()
                if (URLUtil.isValidUrl(url)) {
                    websiteText.text = websiteEditText.text.toString()
                    websiteUrl = websiteEditText.text.toString()
                }
                else {
                    Toast.makeText(activity, "Invalid website url! Include https://", Toast.LENGTH_SHORT).show()
                    websiteUrl = websiteText.text.toString()
                }
            }
            else {
                websiteUrl = websiteText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            websiteText.visibility = View.VISIBLE
            websiteEditText.visibility = View.GONE
            websiteButton.setText("Change website")
        }
    }

    private fun onPhoneButtonClick() {
        if (phoneEditText.visibility == View.GONE) {
            phoneText.visibility = View.GONE
            phoneEditText.visibility = View.VISIBLE
            phoneButton.setText("Save number")
        }
        else {
            if (phoneEditText.text.isNotEmpty()) {
                phoneText.text = phoneEditText.text.toString()
                phoneNumber = phoneEditText.text.toString()
            }
            else {
                phoneNumber = phoneText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            phoneText.visibility = View.VISIBLE
            phoneEditText.visibility = View.GONE
            phoneButton.setText("Change number")
        }
    }

    private fun onNoteButtonClick() {
        if (noteEditText.visibility == View.GONE) {
            noteMsgText.visibility = View.GONE
            noteEditText.visibility = View.VISIBLE
            noteButton.setText("Save note")
        }
        else {
            if (noteEditText.text.isNotEmpty()) {
                noteMsgText.text = noteEditText.text.toString()
                noteMsg = noteEditText.text.toString()
            }
            else {
                noteMsg = noteMsgText.text.toString()
            }
            val imm: InputMethodManager =
                this.context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
            noteMsgText.visibility = View.VISIBLE
            noteEditText.visibility = View.GONE
            noteButton.setText("Change note")
        }
    }

    private fun onClearNoteButtonClick() {
        noteText.text = ""
        noteText.visibility = View.GONE
        clearNoteButton.visibility = View.GONE
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
        // Make sure we have access fine location enabled, if not, prompt the user to enable it
        if (activity!!.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("This app needs location access")
            builder.setMessage("Please grant location access so this app can detect peripherals.")
            builder.setPositiveButton(android.R.string.ok, null)
            builder.setOnDismissListener {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_FINE_LOCATION
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
            PERMISSION_REQUEST_FINE_LOCATION -> {
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

    private fun loadModel() {
        debugTextView.text = "Calibration required"
//        val xFile = File(this.context?.filesDir,"x.txt")
//        val yFile = File(this.context?.filesDir,"y.txt")
//        if(xFile.exists() && yFile.exists()){
//            Log.e("MACROSNAP","Dataset files found.")
//            debugTextView.text = "Found saved calibration"
//            val (x, y) = readData()
//            trainKNN(x, y)
//        } else {
//            Log.e("MACROSNAP","Dataset files not found.")
//            debugTextView.text = "Calibration required"
//        }
    }

    private fun updateGraph(fsr1: Int, fsr2: Int, fsr3: Int) {
        graph1.setData(listOf(DataPoint(0, 5), DataPoint(fsr1, 5)), 255, 10)
        graph2.setData(listOf(DataPoint(0, 5), DataPoint(fsr2, 5)), 255, 10)
        graph3.setData(listOf(DataPoint(0, 5), DataPoint(fsr3, 5)), 255, 10)
    }

    private fun preprocessData(): Pair<Boolean,DoubleArray> {
        // segment data
        val fsr1Filtered = mutableListOf<Double>()
        val fsr2Filtered = mutableListOf<Double>()
        val fsr3Filtered = mutableListOf<Double>()
        val fsrMax = doubleArrayOf(
            max(fsr1Data.toDoubleArray()),
            max(fsr2Data.toDoubleArray()),
            max(fsr3Data.toDoubleArray())
        )
        Log.e("MACROSNAP", "fsrMax " + fsrMax.joinToString(" "))
        val filterThreshold = 0.4 * max(fsrMax)
        var start = false
        for (i in fsr1Data.indices) {
            // make sure to get one continuous segment of data
            val filterValue = abs(fsr1Data[i]) + abs(fsr2Data[i]) + abs(fsr3Data[i])
            if (start && filterValue < filterThreshold) {
                break
            }
            if ((filterValue >= filterThreshold && !start) || start) {
                fsr1Filtered.add(fsr1Data[i])
                fsr2Filtered.add(fsr2Data[i])
                fsr3Filtered.add(fsr3Data[i])
                start = true
            }
        }
        Log.e("MACROSNAP", "fsr1Filtered " + fsr1Filtered.joinToString(" "))
        Log.e("MACROSNAP", "fsr2Filtered " + fsr2Filtered.joinToString(" "))
        Log.e("MACROSNAP", "fsr3Filtered " + fsr3Filtered.joinToString(" "))
        return if (fsr1Filtered.size < 5) {
            Toast.makeText(activity, "Gesture data too short, hold for longer or bring device closer!", Toast.LENGTH_SHORT).show()
            Pair(false, doubleArrayOf())
        } else {
            // calculate mean
            Pair(true, doubleArrayOf(
                mean(fsr1Filtered.toDoubleArray()),
                mean(fsr2Filtered.toDoubleArray()),
                mean(fsr3Filtered.toDoubleArray())
            ))
        }
    }

    private fun crossValidation(X: Array<DoubleArray>, Y: IntArray): DoubleArray {
        val kFold = 3
        // shuffle dataset
        val shuffleIndex = List(X.size) { Random.nextInt(0, X.size-1) }
        val shuffledX = X.slice(shuffleIndex)
        val shuffledY = Y.slice(shuffleIndex)
        // split dataset into k partitions
        val splitX = shuffledX.toList().chunked(Y.size / kFold)
        val splitY = shuffledY.toList().chunked(Y.size / kFold)
        // k-fold cross validation
        val nClasses = 5
        val numCorrect = MutableList(nClasses) { 0 }
        val numTotal = MutableList(nClasses) { 0 }
        for (i in 0 until kFold) {
            val testX = splitX[i]
            val testY = splitY[i]
            val trainX: MutableList<DoubleArray> = ArrayList()
            val trainY: MutableList<Int> = ArrayList()
            for (j in 0 until kFold) {
                if (i != j) {
                    trainX += splitX[j]
                    trainY += splitY[j]
                }
            }
            Log.e("MACROSNAP", "Running cross validation split $i")
            Log.e("MACROSNAP", "Y " + Y.joinToString(" "))
            Log.e("MACROSNAP", "testY " + testY.toIntArray().joinToString(" "))
            Log.e("MACROSNAP", "trainY " + trainY.toIntArray().joinToString(" "))
            // train test model
            val model = KNN.fit(shuffledX.toTypedArray(), shuffledY.toIntArray(), 3)
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

    private fun trainKNN(x: Array<DoubleArray>, y: IntArray) {
        // check dataset size
        if (x.size < 1) {
            Toast.makeText(activity, "Not enough data to train KNN, restart the band to start calibration again.", Toast.LENGTH_LONG).show()
            return
        }
        // save dataset
        Log.e("MACROSNAP", "KNN training dataset size " + y.size.toString())
        val xFile = File(this.context?.filesDir,"x.txt")
        val yFile = File(this.context?.filesDir,"y.txt")
        if(!xFile.exists() || !yFile.exists()){
            writeData(x, y)
        }
        // determine top 3 gestures based on cross validation accuracy
        val classAccuracies = crossValidation(x, y)
        val meanAcc = mean(classAccuracies)
        debugTextView.text = "KNN cross validation mean acc: %.4f".format(meanAcc)
        Log.e("MACROSNAP", "KNN cross validation mean acc: $meanAcc")
        val indices = arrayOf(0, 1, 2, 3, 4)
        val sortedIndices = indices.sortedByDescending { classAccuracies[it] }
        gesture1Accuracy = classAccuracies[sortedIndices[0]]
        gesture2Accuracy = classAccuracies[sortedIndices[1]]
        gesture3Accuracy = classAccuracies[sortedIndices[2]]
        gestureSelection1 = sortedIndices[0] + 1
        gestureSelection2 = sortedIndices[1] + 1
        gestureSelection3 = sortedIndices[2] + 1
        spinner1.setSelection(gestureSelection1)
        spinner2.setSelection(gestureSelection2)
        spinner3.setSelection(gestureSelection3)
        Log.e("MACROSNAP", "Task 1 gesture $gestureSelection1 acc: %.4f".format(gesture1Accuracy))
        Log.e("MACROSNAP", "Task 2 gesture $gestureSelection2 acc: %.4f".format(gesture2Accuracy))
        Log.e("MACROSNAP", "Task 3 gesture $gestureSelection3 acc: %.4f".format(gesture3Accuracy))
        // train on full dataset
        knn = KNN.fit(x, y, 3)
//        datasetY.clear()
//        datasetX.clear()
    }

    private fun emptyData() {
        dataIndex = 0
        fsr1Data.clear()
        fsr2Data.clear()
        fsr3Data.clear()
    }

    private fun updateUI(status: Int) {
        Log.i("MACROSNAP", "Updating UI status: $status")
        if (status == 0 && prevCalib == 8) {
            viewSwitcher.showNext()  // switch from normal to calibration UI
        } else if (status == 8 && prevCalib < 8) {
            viewSwitcher.showPrevious()  // switch from calibration to normal UI
        }
        if (status != 8) {
            setCalibrationUI(status)
        }
        prevCalib = status
    }

    private fun setCalibrationUI(status: Int) {
        Log.e("MACROSNAP", "Set Calibration UI $status")
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        if (status == 0) {
            calibTitle.text = "Start Calibration"
            calibDescription.text = resources.getString(R.string.calib_start)
            val imageId = resources.getIdentifier("armband_placement", "drawable", activity?.packageName)
            calibImage.setImageResource(imageId)
        }
        else if (status == 1) {
            calibTitle.text = "Snap Calibration"
            val imageId = resources.getIdentifier(calibImageDrawables[status-1], "drawable", activity?.packageName)
            calibImage.setImageResource(imageId)
            calibDescription.text = calibDescriptionText[status-1]
        }
        else if (status < 7) {
            val gestureText = GestureType.values()[status-1].str
            calibTitle.text = "Gesture Calibration: $gestureText"
            val imageId = resources.getIdentifier(calibImageDrawables[status-1], "drawable", activity?.packageName)
            calibImage.setImageResource(imageId)
            calibDescription.text = calibDescriptionText[status-1]
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE
            progressText.text = "$currGestureCount/5"
            progressBar.progress = 0
            for (i in 1..currGestureCount) {
                progressBar.incrementProgressBy(20)
            }
        }
        else if (status == 7) {
            calibTitle.text = "Calibration Complete!"
            val gesture1Text = GestureType.values()[gestureSelection1].str
            val gesture2Text = GestureType.values()[gestureSelection2].str
            val gesture3Text = GestureType.values()[gestureSelection3].str
            calibDescription.text = "Recommended gestures and accuracy: \n  " + gesture1Text + " (set to Task 1) %.2f \n  ".format(gesture1Accuracy*100) + gesture2Text + " (set to Task 2) %.2f \n  ".format(gesture2Accuracy*100) + gesture3Text + " (set to Task 3) %.2f ".format(gesture3Accuracy*100) + "\nClick UI button to start using Macro Snap!"
            calibImage.setImageResource(android.R.color.transparent)
        }
    }

    private fun runAppIntent(gestureIndex: Int) {
        // run task based on given gesture
        when (gestureIndex) {
            0 -> {
                return
            }
            gestureSelection1 -> {
                // run task 1
                tasks?.maps(address)
//                tasks?.alarm("Macro Snap Alarm", alarmHour, alarmMinute)
                return
            }
            gestureSelection2 -> {
                // run task 2
                val secs = timerHour * 3600 + timerMinute * 60 + timerSecond
                tasks?.timer("Macro Snap Timer", secs)
                return
            }
            gestureSelection3 -> {
                // run task 3
                Log.e("MACROSNAP", "spotify link $playlistLink")
                val playlistId = playlistLink.substringAfter("playlist/").substringBefore('?')
                Log.e("MACROSNAP", "spotify $playlistId")
                tasks?.spotify(playlistId)
//                val geoloc = Uri.parse("geo:0,0?q=" + address)
//                tasks?.maps(geoloc)
                return
            }
            gestureSelection4 -> {
                // run task 4
                tasks?.spotifyNext()
                return
            }
            gestureSelection5 -> {
                if (startRecordButton.visibility == View.VISIBLE) {
                    startRecording()
                }
                else {
                    stopRecording()
                }
                return
            }
            gestureSelection6 -> {
                tasks?.website(websiteUrl)
                return
            }
            gestureSelection7 -> {
                tasks?.call(phoneNumber)
                return
            }
            gestureSelection8 -> {
                val time = Calendar.getInstance().time
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm")
                val newNote = formatter.format(time) + ": " + noteMsg + "\n"
                noteText.text = noteText.text.toString() + newNote
                if (noteText.visibility == View.GONE) {
                    noteText.visibility = View.VISIBLE
                    clearNoteButton.visibility = View.VISIBLE
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
                    val battery = scanData[0].toUByte().toUInt().toInt()
                    val fsr1 = scanData[1].toUByte().toUInt().toInt()
                    val fsr2 = scanData[2].toUByte().toUInt().toInt()
                    val fsr3 = scanData[3].toUByte().toUInt().toInt()
                    val button = scanData[4].toUByte().toUInt().toInt()
                    val status = scanData[5].toUByte().toUInt().toInt()
                    val connected = scanData[6].toUByte().toUInt().toInt()
                    val gesture = scanData[7].toUByte().toUInt().toInt()
                    Log.i(
                        "MACROSNAP",
                        "device $deviceName, vbat $battery, fsrs [$fsr1, $fsr2, $fsr3], button $button, status $status, connected $connected, gesture $gesture"
                    )
                    debugTextView.text = "$deviceName: vbat $battery, fsrs [$fsr1, $fsr2, $fsr3], button $button, status $status, connected $connected, gesture $gesture"

                    // battery low
                    if (battery > 60) {
                        Toast.makeText(activity, "Wearable battery low!", Toast.LENGTH_SHORT).show()
                    }

                    updateGraph(fsr1, fsr2, fsr3)

                    // only store raw FSR values if
                    // gesture is 1 -> snap was detected
                    // needRestValue is true -> initial scans being received
                    if (gesture == 1 || needRestValue) {
                        val normFsr1 = fsr1.toDouble() - fsrRestValue[0]
                        val normFsr2 = fsr2.toDouble() - fsrRestValue[1]
                        val normFsr3 = fsr3.toDouble() - fsrRestValue[2]
                        fsr1Data.add(dataIndex, normFsr1)
                        fsr2Data.add(dataIndex, normFsr2)
                        fsr3Data.add(dataIndex, normFsr3)
                        dataIndex += 1
                        if (needRestValue) {
                            debugTextView.text = "Maintain resting position.. FSR1 %.3f FSR2 %.3f FSR3 %.3f".format(normFsr1, normFsr2, normFsr3)
                        }
                        else {
                            debugTextView.text = "Recording gesture.. FSR1 %.3f FSR2 %.3f FSR3 %.3f".format(normFsr1, normFsr2, normFsr3)
                        }
                        Log.e("MACROSNAP", "Stored data FSR1 %.4f FSR2 %.4f FSR3 %.4f".format(normFsr1, normFsr2, normFsr3))
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

                    if (button == 1 && status == 9) {
                        cancelGesture = true
                    }

                    // run prediction
                    if (prevGestureStatus == 1 && gesture == 0 && status == 8) {
                        if (cancelGesture) {
                            Toast.makeText(activity, "Gesture cancelled", Toast.LENGTH_SHORT).show()
                        } else {
                            val (success, fsrFeatures) = preprocessData()
                            if (success) {
//                            Toast.makeText(activity, "Calculated features "+ fsrFeatures.joinToString(), Toast.LENGTH_SHORT).show()
                                Log.e("MACROSNAP", "Processed features " + fsrFeatures.joinToString())
                                // run model
                                val pred = knn!!.predict(fsrFeatures) + 1 // type int
                                val detectedGesture = GestureType.values()[pred]
                                Log.e("MACROSNAP", "Predicted gesture " + detectedGesture.str)
                                Toast.makeText(activity, "Predicted gesture " + detectedGesture.str, Toast.LENGTH_SHORT).show()
                                // run task
                                runAppIntent(pred)
                            } else {
                                Log.e("MACROSNAP", "Processed features failed")
                            }
                        }
                        // reset list
                        emptyData()
                    }

                    if (gesture == 2) {
                        while (currGestureCount > 0) {
                            datasetX.removeLast()
                            datasetY.removeLast()
                            currGestureCount -= 1
                        }
                    }

                    // run gesture calibration
                    if (prevGestureStatus == 1 && gesture == 0 && status >= 2 && status < 8) {
                        Log.e("MACROSNAP", "Amount data stored " + (dataIndex+1).toString())
                        Log.e("MACROSNAP", "fsr1Data " + fsr1Data.joinToString(" "))
                        Log.e("MACROSNAP", "fsr2Data " + fsr2Data.joinToString(" "))
                        Log.e("MACROSNAP", "fsr3Data " + fsr3Data.joinToString(" "))
                        val (success, fsrFeatures) = preprocessData()
                        if (success) {
                            Toast.makeText(activity, "Calculated features "+ fsrFeatures.joinToString(), Toast.LENGTH_SHORT).show()
                            Log.e("MACROSNAP", "Processed features " + fsrFeatures.joinToString())
                            val label = status - 2
                            datasetX.add(fsrFeatures)
                            datasetY.add(label)
                            currGestureCount += 1
                            Log.e("MACROSNAP", "Label $label gestures recorded $currGestureCount")
                        }
                        else {
                            Log.e("MACROSNAP", "Processed features failed")
                        }
                        // reset list
                        emptyData()
                    }

                    // calibration data collection completed
                    if (prevCalibStatus == 6 && status == 7) {
                        trainKNN(datasetX.toTypedArray(), datasetY.toIntArray())
                    }

                    // update UI
                    if (status <= 8) {
                        updateUI(status)
                    }

                    if (prevCalibStatus != status) {
                        currGestureCount = 0
                    }
                    if (prevGestureStatus != gesture) {
                        cancelGesture = false
                    }
                    prevCalibStatus = status
                    prevGestureStatus = gesture
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MACROSNAP", errorCode.toString())
        }
    }

}