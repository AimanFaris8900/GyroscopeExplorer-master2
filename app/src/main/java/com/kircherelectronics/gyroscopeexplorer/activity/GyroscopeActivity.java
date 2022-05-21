package com.kircherelectronics.gyroscopeexplorer.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import com.kircherelectronics.fsensor.filter.averaging.MeanFilter;
import com.kircherelectronics.fsensor.observer.SensorSubject;
import com.kircherelectronics.fsensor.sensor.FSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.ComplementaryGyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.KalmanGyroscopeSensor;
import com.kircherelectronics.gyroscopeexplorer.R;
import com.kircherelectronics.gyroscopeexplorer.datalogger.DataLoggerManager;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeBearing;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeRotation;
import com.kircherelectronics.gyroscopeexplorer.view.VectorDrawableButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Scanner;
import java.util.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/*
 * Copyright 2013-2017, Kaleb Kircher - Kircher Engineering, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The main activity displays the orientation estimated by the sensor(s) and
 * provides an interface for the user to modify settings, reset or view help.
 *
 * @author Kaleb
 */
public class GyroscopeActivity extends AppCompatActivity {
    private final static int WRITE_EXTERNAL_STORAGE_REQUEST = 1000;

    // Indicate if the output should be logged to a .csv file
    private boolean logData = false;

    private boolean meanFilterEnabled;

    private float[] fusedOrientation = new float[3];

    // The gauge views. Note that these are views and UI hogs since they run in
    // the UI thread, not ideal, but easy to use.
    private GaugeBearing gaugeBearingCalibrated;
    private GaugeRotation gaugeTiltCalibrated;

    // Handler for the UI plots so everything plots smoothly
    protected Handler uiHandler;
    protected Runnable uiRunnable;

    private TextView tvXAxis;
    private TextView tvYAxis;
    private TextView tvZAxis;
    private TextView water_value;

    private FSensor fSensor;

    private MeanFilter meanFilter;

    private DataLoggerManager dataLogger;

    private Dialog helpDialog;

    public Context mContext;
    /*
    public GyroscopeActivity(Context context){
        this.mContext = context;
    }

    public GyroscopeActivity(){

    }
    */
    //GyroscopeActivity gyroscopeactivity = new GyroscopeActivity(mContext);



    private SensorSubject.SensorObserver sensorObserver = new SensorSubject.SensorObserver() {
        @Override
        public void onSensorChanged(float[] values) {
          updateValues(values);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gyroscope);
        dataLogger = new DataLoggerManager(this);
        meanFilter = new MeanFilter();

        uiHandler = new Handler();
        uiRunnable = new Runnable() {
            @Override
            public void run() {
                uiHandler.postDelayed(this, 100);
                updateText();
                updateGauges();
            }
        };

        initUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.gyroscope, menu);
        return true;
    }

    /**
     * Event Handling for Individual menu item selected Identify single menu
     * item by it's id
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reset:
                fSensor.reset();
                break;
            case R.id.action_config:
                Intent intent = new Intent();
                intent.setClass(this, ConfigActivity.class);
                startActivity(intent);
                break;
            case R.id.action_help:
                showHelpDialog();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public void onResume() {
        super.onResume();

        Mode mode = readPrefs();

        switch (mode) {
            case GYROSCOPE_ONLY:
                fSensor = new GyroscopeSensor(this);
                break;
            case COMPLIMENTARY_FILTER:
                fSensor = new ComplementaryGyroscopeSensor(this);
                ((ComplementaryGyroscopeSensor)fSensor).setFSensorComplimentaryTimeConstant(getPrefImuOCfQuaternionCoeff());
                break;
            case KALMAN_FILTER:
                fSensor = new KalmanGyroscopeSensor(this);
                break;
        }

        fSensor.register(sensorObserver);
        fSensor.start();
        uiHandler.post(uiRunnable);
    }

    @Override
    public void onPause() {
        if(helpDialog != null && helpDialog.isShowing()) {
            helpDialog.dismiss();
        }

        fSensor.unregister(sensorObserver);
        fSensor.stop();
        uiHandler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted, yay! Do the
                // contacts-related task you need to do.

                startDataLog();
            }
        }
    }

    private boolean getPrefMeanFilterEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.MEAN_FILTER_SMOOTHING_ENABLED_KEY, false);
    }

    private float getPrefMeanFilterTimeConstant() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.MEAN_FILTER_SMOOTHING_TIME_CONSTANT_KEY, "0.5"));
    }

    private boolean getPrefKalmanEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.KALMAN_QUATERNION_ENABLED_KEY, false);
    }

    private boolean getPrefComplimentaryEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.COMPLIMENTARY_QUATERNION_ENABLED_KEY, false);
    }

    private float getPrefImuOCfQuaternionCoeff() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.COMPLIMENTARY_QUATERNION_COEFF_KEY, "0.5"));
    }

    private void initStartButton() {
        final VectorDrawableButton button = findViewById(R.id.button_start);

        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logData) {
                    button.setText(getString(R.string.action_stop));
                    startDataLog();
                } else {
                    button.setText(getString(R.string.action_start));
                    stopDataLog();
                }
            }
        });
    }

    /**
     * Initialize the UI.
     */
    private void initUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if(getSupportActionBar()!=null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Initialize the calibrated text views
        tvXAxis = this.findViewById(R.id.value_x_axis_calibrated);
        tvYAxis = this.findViewById(R.id.value_y_axis_calibrated);
        tvZAxis = this.findViewById(R.id.value_z_axis_calibrated);
        water_value = this.findViewById(R.id.water_level);

        // Initialize the calibrated gauges views
        gaugeBearingCalibrated = findViewById(R.id.gauge_bearing_calibrated);
        gaugeTiltCalibrated = findViewById(R.id.gauge_tilt_calibrated);

        initStartButton();
    }

    /*private String readCSV(){

    }
    */
    private String csvWaterLevel(){
        String water_class = "Undetected";

        double x = (Math.toDegrees(fusedOrientation[1]) + 360)%360;
        double y = (Math.toDegrees(fusedOrientation[2]) + 360)%360;
        double z = (Math.toDegrees(fusedOrientation[0]) + 360)%360;

        try {
            water_class = kNN(x,y,z);
        } catch (FileNotFoundException e) {
            System.out.println("FILE NOT OPEN");
        }

        return water_class;
    }

    /*
    private BufferedReader readTXT(String filename){
        BufferedReader inputreader = null;

        try {
            inputreader = new BufferedReader(new FileReader(filename));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return inputreader;
    }
    */

    private String kNN(double x, double y, double z) throws FileNotFoundException {
        //Scanner sc = new Scanner(new FileInputStream("All_Dataset_with_header.csv"));
        //sc.useDelimiter(",");

       // AssetManager am = mContext.getAssets();

        int column = 0; int row = 0; int k = 10; int r = 0; int c = 0;
        int c1=0; int c2 = 0; int c3 = 0;
        String classes = " "; String line = null;
        double distance = 0.0; double x1 = 0.0; double y1 = 0.0; double z1 = 0.0;
        //ArrayList<Double> calcdist = new ArrayList<Double>();
        //ArrayList<Double> classes = new ArrayList<Double>();
        ArrayList<ArrayList<Double>> calcdist = new ArrayList<ArrayList<Double>>();
        /*ArrayList<ArrayList<Double>> data = new ArrayList<ArrayList<Double>>();*/


        /*try {
            InputStream is = am.open("All_Dataset_with_header.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            while ((line = reader.readLine())!=null){
                String[] values = line.split(",");
                for (String str : values){
                    data.get(r).add(c, Double.valueOf(str));
                    c++;
                }
                r++;
                c=0;
            }
            reader.close();

        } catch (IOException e) {
            e.printStackTrace();
        }*/


        /*
        while (sc.hasNext()){
            data.get(r).add(c, Double.valueOf(sc.next()));
            c++;

            if(c==4){
                r++;
                c = 0;
            }
        }
        */

        double[][] data = {
                {0.13630596,-0.2863911,-0.004070208,0},
                {0.21819188,-0.3135648,0.014338908,0},
                {0.10412555,-0.27530998,-0.015391288,0},
                {0.11677139,-0.29810342,-0.020336838,0},
                {3.0556316,0.3564634,2.7831793,0},
                {2.9991248,0.3010103,2.7330465,0},
                {-0.12507036,0.15883729,-0.06747524,1},
                {-1.8259552,-0.21118265,0.98474336,1},
                {-1.4762443,-0.40836722,-1.9013332,1},
                {-1.5047815,-0.39586687,-1.9324425,1},
                {-1.9974098,-0.2150559,1.0886102,1},
                {-1.9530728,-0.51188457,0.78567517,1},
                {0.023190808,0.1184,-0.107627004,2},
                {2.8909504,-0.29836696,3.035178,2},
                {-0.024435908,0.076398075,-0.060051218,2},
                {-0.09682627,0.15615326,-0.04663066,2},
                {0.05636041,-0.04285817,-0.005389758,2},
                {0.27133352,0.26261413,0.006070457,2}
        };

        //0 = X, 1 = Y, 2 = Z

        for(int i = 0; i<data.length; i++)
        {
            //For data from CSV
            /*x1 = data.get(column).get(0);
            y1 = data.get(column).get(1);
            z1 = data.get(column).get(2);*/

            //For dummy data
            //x1 = data[column][0];
            y1 = data[column][1];
            z1 = data[column][2];

            //distance = Math.sqrt(((x1-x)*(x1-x)) + ((y1-y)*(y1-y)) + ((z1-z)*(z1-z)));
            distance = Math.sqrt(((y1-y)*(y1-y)) + ((z1-z)*(z1-z)));

            //For CSV data
            //calcdist.add(new ArrayList<Double>(Arrays.asList(distance,data.get(column).get(3))));

            calcdist.add(new ArrayList<Double>(Arrays.asList(distance,data[column][3])));

            //calcdist.add(distance);
            //tempdist.add(distance);
            //classes.add(data[column][3]);

            column++;
        }

        Collections.sort(calcdist, new Comparator<ArrayList<Double>>(){
            @Override
            public int compare(ArrayList<Double> o1, ArrayList<Double> o2){
                return o1.get(0).compareTo(o2.get(0));
            }
        });

        System.out.println(calcdist.get(0).get(1));
        System.out.println(calcdist.get(1).get(1));
        System.out.println(calcdist.get(2).get(1));
        System.out.println(calcdist.get(3).get(1));
        System.out.println(calcdist.get(4).get(1));

        for(int j = 0; j<k; j++){

            if(calcdist.get(j).get(1) == 0.0){
                c1++;
                System.out.println("C1 "+c1);
            }
            else if(calcdist.get(j).get(1) == 1.0){
                c2++;
                System.out.println("C2 "+c2);
            }
            else if(calcdist.get(j).get(1) == 2.0){
                c3++;
                System.out.println("C3 "+c3);
            }
        }

        if((c1>c2)&&(c1>c3)){
            System.out.println("The class is 0");
            classes = "Ankle";
        }
        else if((c2>c3)&&(c2>c1)){
            System.out.println("The class is 1");
            classes = "Calf";
        }
        else if((c3>c1)&&(c3>c2)){
            System.out.println("The class is 2");
            classes = "Knee";
        }
        return classes;
    }


    private Mode readPrefs() {
        meanFilterEnabled = getPrefMeanFilterEnabled();
        boolean complimentaryFilterEnabled = getPrefComplimentaryEnabled();
        boolean kalmanFilterEnabled = getPrefKalmanEnabled();

        if(meanFilterEnabled) {
            meanFilter.setTimeConstant(getPrefMeanFilterTimeConstant());
        }

        Mode mode;

        if(!complimentaryFilterEnabled && !kalmanFilterEnabled) {
            mode = Mode.GYROSCOPE_ONLY;
        } else if(complimentaryFilterEnabled) {
            mode = Mode.COMPLIMENTARY_FILTER;
        } else {
            mode = Mode.KALMAN_FILTER;
        }

        return mode;
    }

    private void showHelpDialog() {
        helpDialog = new Dialog(this);
        helpDialog.setCancelable(true);
        helpDialog.setCanceledOnTouchOutside(true);
        helpDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = getLayoutInflater().inflate(R.layout.layout_help_home, (ViewGroup) findViewById(android.R.id.content), false);
        helpDialog.setContentView(view);
        helpDialog.show();
    }

    private void startDataLog() {
        if(!logData && requestPermissions()) {
            logData = true;
            dataLogger.startDataLog();
        }
    }

    private void stopDataLog() {
        if(logData) {
            logData = false;
            String path = dataLogger.stopDataLog();
            Toast.makeText(this, "File Written to: " + path, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateText() {
        tvXAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[1]) + 360) % 360));
        tvYAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[2]) + 360) % 360));
        tvZAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[0]) + 360) % 360));
        water_value.setText(String.format(Locale.getDefault(),csvWaterLevel()));
    }

    private void updateGauges() {
        gaugeBearingCalibrated.updateBearing(fusedOrientation[0]);
        gaugeTiltCalibrated.updateRotation(fusedOrientation[1], fusedOrientation[2]);
    }

    private boolean requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_EXTERNAL_STORAGE_REQUEST);
            return false;
        }

        return true;
    }

    private void updateValues(float[] values) {
        fusedOrientation = values;
        if(meanFilterEnabled) {
            fusedOrientation = meanFilter.filter(fusedOrientation);
        }

        if(logData) {
            dataLogger.setRotation(fusedOrientation);
        }
    }

    private enum Mode {
        GYROSCOPE_ONLY,
        COMPLIMENTARY_FILTER,
        KALMAN_FILTER
    }

}
