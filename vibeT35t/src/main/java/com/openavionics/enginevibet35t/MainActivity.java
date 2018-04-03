package com.openavionics.enginevibet35t;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.openavionics.utils.FixedRateBuffer;
import com.openavionics.utils.file.AudioFileException;
import com.openavionics.utils.file.WavFile;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

	private static final long minimumXlrDelay_us = 10; // that's under audio sampling rate. who knows whether we'll see close to it
	private static final long dataSampleRate_Hz = 44100;
	protected FixedRateBuffer xBuffer = new FixedRateBuffer(dataSampleRate_Hz);
	protected FixedRateBuffer yBuffer = new FixedRateBuffer(dataSampleRate_Hz);
	protected FixedRateBuffer zBuffer = new FixedRateBuffer(dataSampleRate_Hz);
	protected FixedRateBuffer xyzBuffer = new FixedRateBuffer(dataSampleRate_Hz);
	String timeStamp = "";
	private Button button;
	private TextView xValue;
	private TextView yValue;
	private TextView zValue;
	private TextView magValue;
	private TextView tValue;
	private TextView srValue;
	private boolean xlromRunning;

	protected Handler periodicUpdateHandler = null;
	protected Runnable periodicUpdater = null;
	protected long uiUpdateRate = 100;

	// low pass filter to remove gravity
	// time constant ~ -(sample time) * ln(alpha)
	// alph ~ exp(time const /sample time)
	// fcut ~ -ln(alpha)*(sampleRate/2*pi)
	protected float lowPassAlpha = 0.8f; // this is a bit arbitrary nonetheless. reccomendation from android sources, but seems to be ok.
	protected boolean enableLowPassGravityFilter = true;
	private boolean lowPassGravityFilterRun = true;

	private SensorManager sensorManager = null;
	private Sensor accelerometer = null;
	private float[] gravity = new float[] {0, 0, 0};
	private double sampleRate_hz = 0.0;
	private long nSamples = 0;
	private long startTime_ms = 0;
	private long lastSampleTime_us = 0;
	
	private float axRaw;
	private float ayRaw;
	private float azRaw;
	private float axyzRaw;
	private TextView deviceDescriptionView;
	private TextView errorView;
	private boolean storeWave;
	private TextView gValue;
	private CheckBox enableGFilterCheckBox;
	private EditText editGFilterAlpha;
	private float g;
	// rough approximation of standard audio sample rate. xlrometr is probably super noisy at this level
	/*
	private Sensor magnetometer = null;
	private Sensor gyro = null;
	private Sensor luxometer = null;
	private Sensor proxometer = null;
	private Sensor rotometer = null;
	*/
	/*
	From the docs:
	"The default data delay is suitable for monitoring typical screen orientation changes
	 and uses a delay of 200,000 microseconds. You can specify other data delays, such as SENSOR_DELAY_GAME (20,000 microsecond delay),
	  SENSOR_DELAY_UI (60,000 microsecond delay), or SENSOR_DELAY_FASTEST (0 microsecond delay). As of Android 3.0 (API Level 11) you
	  can also specify the delay as an absolute value (in microseconds)."
	 BUT
	 "The delay that you specify is only a suggested delay. The Android system and other applications can alter this delay"

	 for reference, standard audio would have 22.6757 us delay, 1000000/44100
	 */

	MainActivity() {
	}

	int accelerometerSamplingPeriod = SensorManager.SENSOR_DELAY_FASTEST;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		storeWave = true;
		g = 0;
		button = (Button) findViewById(R.id.button);
		button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (xlromRunning) {
					button.setText("Start");
					stopSensors();
				} else {
					button.setText("Stop");
					startSensors();
				}
			}
		});
		enableGFilterCheckBox = (CheckBox) findViewById(R.id.enableGFilter);
		enableGFilterCheckBox.setChecked(enableLowPassGravityFilter);
		enableGFilterCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				enableLowPassGravityFilter = isChecked;
			}
		});

		editGFilterAlpha = (EditText) findViewById(R.id.editGFilterAlpha);
		editGFilterAlpha.setText(Float.toString(lowPassAlpha));
		editGFilterAlpha.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				lowPassAlpha = Float.parseFloat(s.toString());
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});


		xValue = (TextView) findViewById(R.id.xValue);
		yValue = (TextView) findViewById(R.id.yValue);
		zValue = (TextView) findViewById(R.id.zValue);
		magValue = (TextView) findViewById(R.id.magValue);
		tValue = (TextView) findViewById(R.id.tValue);
		srValue = (TextView) findViewById(R.id.srValue);
		deviceDescriptionView = (TextView) findViewById(R.id.deviceDescription);
		errorView = (TextView) findViewById(R.id.errorView);
		gValue = (TextView) findViewById(R.id.gValue);
		setupSensors();

		periodicUpdateHandler = new Handler();
		periodicUpdater  = new Runnable() {
			@Override
			public void run()
			{
				onPeriodicUiUpdate();
				periodicUpdateHandler.postDelayed(periodicUpdater, uiUpdateRate);
			}

		};

		if (PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED) {
			Log.d("onCreate", "we've got permissions");
		} else {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (permissions[0] == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
			if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				Log.d("onPermissionsResult", "Permission: " + permissions[0] + "was " + grantResults[0]);
				//resume tasks needing this permission
			} else {
				storeWave = false;
			}
		}
	}

	/**
	 * The activity is about to become visible.
	 */
	@Override
	protected void onStart()
	{
		Log.d("onStart", "about to start");
		super.onStart();
		startPeriodicUiUpdate();
	}

	/**
	 * The activity has become visible (it is now "resumed").
	 */
	@Override
	protected void onResume()
	{
		Log.d("onResume", "about to resume");
		super.onResume();
		resumeSensors();
		startPeriodicUiUpdate();
	}

	/**
	 *  Another activity is taking focus (this activity is about to be "paused").
	 */
	@Override
	protected void onPause()
	{
		Log.d("onPause", "about to pause");
		super.onPause();
		pauseSensors();
		stopPeriodicUiUpdate();
		Log.d("onPause", "paused ok");
	}

	/**
	 * The activity is no longer visible (it is now "stopped")
	 */
	@Override
	protected void onStop()
	{
		Log.d("onStop", "about to stop");
		super.onStop();
		pauseSensors();
		stopPeriodicUiUpdate();
		Log.d("onStop", "stopped ok");
	}

	/**
	 * The activity is about to be destroyed.
	 */
	@Override
	protected void onDestroy()
	{
		Log.d("onDestroy", "entering destruction");
		sensorManager.unregisterListener(this);
		Looper l = getMainLooper();
		Log.d("onDestroy", "looper thread "+l.getThread().getId());
		super.onDestroy();
		Log.d("onDestroy", "dead ok");
	}

	/**
	 * @see android.app.Activity#onBackPressed()
	 */
	@Override
	public void onBackPressed()
	{
		bailAlog("Really Quit?");
//		super.onBackPressed();
	}


	protected void bailAlog(String string)
	{
		AlertDialog.Builder d = new AlertDialog.Builder(this);
		d.setTitle("Going so soon?");
		d.setMessage(string);
		d.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				finish();
			}
		});
		d.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				dialog.dismiss();
			}
		});
		d.show();
	}

	private void startSensors() {
		if (!xlromRunning) {
			xBuffer.reset(0);
			yBuffer.reset(0);
			zBuffer.reset(0);
			xyzBuffer.reset(0);
			xlromRunning = true;
			lowPassGravityFilterRun = enableLowPassGravityFilter;
			resumeSensors();
		}
	}

	private void stopSensors() {
		if (xlromRunning) {
			pauseSensors();
			xlromRunning = false;
			bufferWriter(xBuffer.data(), dataSampleRate_Hz, new String("x-data-")+timeStamp); xBuffer.reset(0);
			bufferWriter(yBuffer.data(), dataSampleRate_Hz, new String("y-data-")+timeStamp); yBuffer.reset(0);
			bufferWriter(zBuffer.data(), dataSampleRate_Hz, new String("z-data-")+timeStamp); zBuffer.reset(0);
			bufferWriter(xyzBuffer.data(), dataSampleRate_Hz, new String("xyz-data-")+timeStamp); xyzBuffer.reset(0);

		}

	}

	private void setupSensors()
	{
		Log.d("setupSensors", "enterring setup...");
		xlromRunning = false;
		sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
		if (accelerometer == null) {
			accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
			if (accelerometer != null) {
				deviceDescriptionView.setText(accelerometer.getVendor()+ " " + accelerometer.getName()
						+ ", range "+accelerometer.getMaximumRange());
			} else {
				deviceDescriptionView.setText("no accelerometer");
			}
		}
		/*
		if (rotometer == null) {
			rotometer = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		}
		if (proxometer == null) {
			proxometer = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
		}
		if (luxometer == null) {
			luxometer = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
		}
		if (magnetometer == null) {
			magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		}
		if (gyro == null) {
			gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
		}
		*/
	}


	private void resumeSensors()
	{
		if (xlromRunning) {
			if (accelerometer != null) {
				sensorManager.registerListener(this, accelerometer, accelerometerSamplingPeriod);
				startTime_ms = System.nanoTime() / 1000000; // not getCurrentMillis() ... just to keep consistent with Timer calls
				nSamples = 0;
				timeStamp = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(new Date());
			}
			/*
			if (rotometer != null) sensorManager.registerListener(this, rotometer, SensorManager.SENSOR_DELAY_NORMAL);
			if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL);
			if (proxometer != null) sensorManager.registerListener(this, proxometer, SensorManager.SENSOR_DELAY_NORMAL);
			if (magnetometer != null) sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
			if (luxometer != null) sensorManager.registerListener(this, luxometer, SensorManager.SENSOR_DELAY_NORMAL);
			*/
		}
	}


	protected void startPeriodicUiUpdate()
	{
//		Log.d("updater", "start periodic");
		periodicUpdater.run();
	}

	protected void stopPeriodicUiUpdate()
	{
		periodicUpdateHandler.removeCallbacks(periodicUpdater);
	}

	String displayFrequency(double hz) {
		return Double.toString(hz) + " Hz";
	}
	protected void onPeriodicUiUpdate() {
		if (xlromRunning) {
			xValue.setText(Float.toString(axRaw));
			yValue.setText(Float.toString(ayRaw));
			zValue.setText(Float.toString(azRaw));
			magValue.setText(Float.toString(axyzRaw));
			tValue.setText(Float.toString((lastSampleTime_us-startTime_ms*1000)/1000000.0f) + " secs");
			srValue.setText(displayFrequency(sampleRate_hz));
			gValue.setText(Float.toString(g));
		}
	}


	private void pauseSensors()
	{
		if (xlromRunning) {
			if (accelerometer != null) sensorManager.unregisterListener(this, accelerometer);
			/*
			if (rotometer != null) sensorManager.unregisterListener(this, rotometer);
			if (gyro != null) sensorManager.unregisterListener(this, gyro);
			if (proxometer != null) sensorManager.unregisterListener(this, proxometer);
			if (magnetometer != null) sensorManager.unregisterListener(this, magnetometer);
			if (luxometer != null) sensorManager.unregisterListener(this, luxometer);
			*/
		}
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1)
	{
	}

	@Override
	public void onSensorChanged(SensorEvent event)
	{
//		Log.d("sensor", String.format("roto %f %f %f", event.values[0], event.values[1], event.values[2]));
		if (event.sensor == accelerometer) {
			// alpha is calculated as t / (t + dT)
			// with t, the low-pass filter's time-constant
			// and dT, the event delivery rate

			long now_us = System.nanoTime() / 1000;

			final long now_ms = now_us / 1000;
			nSamples++;
			sampleRate_hz = 1000.0f*((float)nSamples) / (now_ms - startTime_ms);


			if (now_us - lastSampleTime_us > minimumXlrDelay_us) {
				gravity[0] = lowPassAlpha * gravity[0] + (1 - lowPassAlpha) * event.values[0];
				gravity[1] = lowPassAlpha * gravity[1] + (1 - lowPassAlpha) * event.values[1];
				gravity[2] = lowPassAlpha * gravity[2] + (1 - lowPassAlpha) * event.values[2];

				axRaw = event.values[0];
				ayRaw = event.values[1];
				azRaw = event.values[2];

				if (lowPassGravityFilterRun) {
					axRaw -= gravity[0];
					ayRaw -= gravity[1];
					azRaw -= gravity[2];
				}

				axyzRaw = (float) Math.sqrt(axRaw * axRaw + ayRaw * ayRaw + azRaw * azRaw);
				g = (float) Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);

//				Log.d("acceltTestData", String.format("%g/%g @ %d/%d", event.values[0], axRaw, event.timestamp, now_us));

				xBuffer.add(axRaw, now_us);
				yBuffer.add(ayRaw, now_us);
				zBuffer.add(azRaw, now_us);
				xyzBuffer.add(axyzRaw, now_us);

				if (xBuffer.elapsedTime_s() > 10) {
					bufferWriter(xBuffer.data(), dataSampleRate_Hz, new String("x-data-")+timeStamp); xBuffer.reset(now_us);
					bufferWriter(yBuffer.data(), dataSampleRate_Hz, new String("y-data-")+timeStamp); yBuffer.reset(now_us);
					bufferWriter(zBuffer.data(), dataSampleRate_Hz, new String("z-data-")+timeStamp); zBuffer.reset(now_us);
					bufferWriter(xyzBuffer.data(), dataSampleRate_Hz, new String("xyz-data-")+timeStamp); xyzBuffer.reset(now_us);
					timeStamp = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(new Date());
				}
				lastSampleTime_us = now_us;
			}
		}
		/*

		else if (event.sensor == rotometer) {
			float [] rv = new float[3];
			for (int i=0; i<3; i++) {
				rv[i] = (event.values[i]+1)/2;
			}
			for (SXControlAssign sxp: p.sensorAssign) {
				switch (sxp.getSensorId()) {
					case SensorInfo.ROT_X: {
						sxp.setNormalizedValue(rv[0]);
						ccontrolUpdate(sxp.controllable, sxp.xp.ccontrolId(), sxp.x, Controllable.Controller.SENSOR);
						break;
					}

					case SensorInfo.ROT_Y: {
						sxp.setNormalizedValue(rv[1]);
						ccontrolUpdate(sxp.controllable, sxp.xp.ccontrolId(), sxp.x, Controllable.Controller.SENSOR);
						break;
					}

					case SensorInfo.ROT_Z: {
						sxp.setNormalizedValue(rv[2]);
						ccontrolUpdate(sxp.controllable, sxp.xp.ccontrolId(), sxp.x, Controllable.Controller.SENSOR);
						break;
					}

				}

			}
		}*/
	}

	private void bufferWriter(ArrayList<Float> data, long sampleRate, String filename) {
		new Thread(new WaveWriterRunnable(data, sampleRate, filename+".wav", this)).start();
	}

	protected File getStorageBaseDir() throws IOException {
		File f = new File(Environment.getExternalStorageDirectory()+"/"+"openavionics", "xlrtst");
		if (!f.exists()) {
			if (!f.mkdirs()) {
				throw new IOException("mkdirs on '"+f.getAbsolutePath()+"' returns false");
			}
			MediaScannerConnection.scanFile(this, new String[] {f.getAbsolutePath().toString()}, null, null);
		}
		return f;
	}

	/**
	 * copies the buffer, and writes it
	 */
	private class WaveWriterRunnable implements Runnable {
		private final Context context;
		private final float [] data;
		private final long sampleRate;
		private final String filename;
		public WaveWriterRunnable(ArrayList<Float> data, long sampleRate, String filename, Context context) {
			this.data = new float[data.size()];
			for (int i=0; i<data.size(); ++i) {
				this.data[i] = data.get(i);
			}
			this.sampleRate = sampleRate;
			this.filename = filename;
			this.context = context;
		}

		@Override
		public void run() {
			WavFile outFile = new WavFile();
			try {
				outFile.create(new File(getStorageBaseDir(), filename), 1, data.length, 32, sampleRate);
				outFile.writeFrames(data, data.length);
				outFile.close();
				MediaScannerConnection.scanFile(context, new String[] {outFile.getFile().getAbsolutePath().toString()}, null, null);
			} catch (IOException e) {
				setError("WaveWriterRunnable", "io error in writer, " + e.getMessage());
				e.printStackTrace();
				return;
			} catch (AudioFileException e) {
				setError("WaveWriterRunnable", "audio error in writer, " + e.getMessage());
				e.printStackTrace();
				return;
			}
		}
	}

	private void setError(String waveWriterRunnable, final String s) {
		Log.d("WaveWriterRunnable", s);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				button.setText("Start");
				errorView.setText(s);
				stopSensors();
			}
		});

	}
}
