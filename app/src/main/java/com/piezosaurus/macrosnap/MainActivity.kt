package com.piezosaurus.macrosnap

import android.os.Bundle
import android.util.Log
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

import java.io.*
import smile.*
import smile.data.*
import smile.classification.*
import smile.math.kernel.*
import smile.validation.*
import smile.validation.metric.*


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        loadScannerFragment()
        loadSVM()
    }

    private fun loadScannerFragment() {
        val ft = supportFragmentManager.beginTransaction()
        ft.replace(R.id.your_placeholder, ScannerFragment())
        ft.commit()
    }

    private fun writeData(context: Context, x: Array<DoubleArray>, y: IntArray) {
        var fileOutputStream:FileOutputStream
        try {
            fileOutputStream = context.openFileOutput("x.txt", Context.MODE_PRIVATE)
            val objectOutputStream = ObjectOutputStream(fileOutputStream)
            objectOutputStream.writeObject(x)
            objectOutputStream.close()
        } catch (e: Exception){
            e.printStackTrace()
        }

        try {
            fileOutputStream = context.openFileOutput("y.txt", Context.MODE_PRIVATE)
            val objectOutputStream = ObjectOutputStream(fileOutputStream)
            objectOutputStream.writeObject(y)
            objectOutputStream.close()
        } catch (e: Exception){
            e.printStackTrace()
        }
    }

    private fun readData(context: Context): Dataset {
        var fileInputStream: FileInputStream? = null
        fileInputStream = context.openFileInput("x.txt")
        var objectInputStream = ObjectInputStream(fileInputStream)
        val x: Array<DoubleArray> = objectInputStream.readObject() as Array<DoubleArray>
        objectInputStream.close()

        fileInputStream = context.openFileInput("y.txt")
        objectInputStream = ObjectInputStream(fileInputStream)
        val y: IntArray = objectInputStream.readObject() as IntArray
        objectInputStream.close()

        return Dataset(x, y)
    }

    private fun loadSVM() {
        val data = Array<DoubleArray>(10){doubleArrayOf(0.0)}
        data[0] = doubleArrayOf(45.0,14.0,164900.0,116910.0,48.7392861,133.930123 )
        data[1] = doubleArrayOf(43.0,12.0,138633.333,116910.0,30.9357145,138.55321)
        data[2] = doubleArrayOf(66.0,21.0,151266.667,120633.333,57.3142861,139.144051)
        data[3] = doubleArrayOf(47.0,10.0,128500.0,120633.333,23.0073691,123.355951)
        data[4] = doubleArrayOf(40.0,9.0,148766.667,122181.667,43.8676314,122.667478)
        data[5] = doubleArrayOf(146.0,66.0,138566.667,126548.333,20.0705358,109.957522)
        data[6] = doubleArrayOf(118.0,71.0,134733.333,128621.667,27.4578573,83.9544647)
        data[7] = doubleArrayOf(138.0,63.0,127533.333,137826.333,15.9874061,43.041923)
        data[8] = doubleArrayOf(150.0,86.0,109466.667,141869.667,14.8142858,35.7447188)
        data[9] = doubleArrayOf(128.0,72.0,96800.0,141120.333,15.6678573,37.8162068)

        val labels = IntArray(10)
        labels[0] = 0
        labels[1] = 1
        labels[2] = 2
        labels[3] = 3
        labels[4] = 4
        labels[5] = 5
        labels[6] = 6
        labels[7] = 7
        labels[8] = 8
        labels[9] = 9

        writeData(this, data, labels)
        val (x, y) = readData(this)

        val kernel = GaussianKernel(1/6.0)  // gamma = 1/num_features
        val model = ovr(x, y, { x, y -> svm(x, y, kernel, 1.0, 1E-3) })
//        var pred = CrossValidation.classification(10, x, y, {a, b -> ovr(a, b, { c, d -> svm(c, d, kernel, 5.0, 1E-3) })})
        val acc = Accuracy.of(y, model.predict(x))
        Log.e("MACROSNAP", "SVM acc: $acc")
//        println(Accuracy.of(y, model.predict(x)))
//        println(Accuracy.of(y, model.predict(x)))
//        println(ConfusionMatrix.of(y, model.predict(x)))
    }
}
