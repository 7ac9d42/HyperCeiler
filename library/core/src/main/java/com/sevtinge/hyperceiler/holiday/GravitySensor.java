/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.holiday;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

public final class GravitySensor implements SensorEventListener {
	private final SensorManager sensorManager;
	private float[] magneticValues;
	private float[] accelerometerValues;
	private final float[] rotationMatrix = new float[9];
	private final float[] remappedRotationMatrix = new float[9];
	private final float[] orientationAngles = new float[3];
	private int orientation;
	private int speed;
	private boolean started;
	private boolean accelerometerRegistered;
	private boolean magnetometerRegistered;
	private final Context context;
	private final WeatherView weatherView;

	public GravitySensor(Context context, WeatherView weatherView) {
		super();
		this.context = context;
		this.weatherView = weatherView;
		this.sensorManager = (SensorManager)this.context.getSystemService(Context.SENSOR_SERVICE);
	}

	public boolean getStarted() {
		return this.started;
	}

	public void setOrientation(int orient) {
		this.orientation = orient;
	}

	public void setSpeed(int spd) {
		this.speed = spd;
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy) {}

	public void onSensorChanged(SensorEvent event) {
		if (event == null || event.sensor == null) return;
		switch (event.sensor.getType()) {
			case 1: this.accelerometerValues = event.values; break;
			case 2: this.magneticValues = event.values; break;
		}
		if (this.magneticValues == null || this.accelerometerValues == null) return;

		SensorManager.getRotationMatrix(this.rotationMatrix, null, this.accelerometerValues, this.magneticValues);
		SensorManager.remapCoordinateSystem(this.rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, this.remappedRotationMatrix);
		SensorManager.getOrientation(this.remappedRotationMatrix, this.orientationAngles);

		double roll = Math.toDegrees(this.orientationAngles[2]) + Math.random() * 20 - 10;
		switch (this.orientation) {
			case Surface.ROTATION_90:
				roll += 90;
				break;
			case Surface.ROTATION_270:
				roll -= 90;
				break;
			case Surface.ROTATION_180:
				roll += roll > 0 ? 180 : -180;
				break;
		}

		if (roll > 90) roll -= 180;
		else if (roll < -90) roll += 180;

		this.weatherView.setAngleAndSpeed((int) roll,
				this.speed + (int) Math.round(Math.random() * 20 - 10));
	}

	private void registerListener() {
		if (this.sensorManager == null) return;
		if (!this.accelerometerRegistered) {
			Sensor sensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
			this.accelerometerRegistered = sensor != null
					&& this.sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
		}
		if (!this.magnetometerRegistered) {
			Sensor sensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
			this.magnetometerRegistered = sensor != null
					&& this.sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
		}
	}

	private void unregisterListener() {
		if (!this.accelerometerRegistered && !this.magnetometerRegistered) return;
		this.sensorManager.unregisterListener(this);
		this.accelerometerRegistered = false;
		this.magnetometerRegistered = false;
	}

	public void start() {
		this.started = true;
		this.registerListener();
	}

	public void stop() {
		this.started = false;
		this.unregisterListener();
	}

	public void onResume() {
		if (this.started) {
			this.registerListener();
		}
	}

	public void onPause() {
		this.unregisterListener();
	}

	public Context getContext() {
		return this.context;
	}

	public WeatherView getWeatherView() {
		return this.weatherView;
	}

}
