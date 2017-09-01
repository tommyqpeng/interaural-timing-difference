package AuditorySearch;

import java.io.IOException;
import java.io.InputStream;
import lsnn.auditorysearch_v1.MainActivity;

import AuditorySearch.WavReader.WavInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * A wrapper class for duo AudioTracks which can manage interaural timing difference alongside real time playback.
 * @author Tommy Peng
 * @version 1.00
 */
public class ITDTrack {

	private static final int HEADER_SIZE = 44;
	private static String TAG = "ITDTrack";

	/** the sampling rate of this wav file */
	private int rate;
	/** the number of channels in this wav file */
	private int channels;
	/** the number of bytes of data in this wav file */
	private int dataSize;
	/** the activity which is using this ITDTrack */
	private MainActivity activity;
	/** the byte array which represents all audio data from the wav file used to create this track */
	private short[] shortData;
	/** the desired size of the current byte arrays for both the left and right streams */
	private int currentArraySize;
	/** the current array which has been fed to the AudioTrack object */
	private short[] currentRightByteData, currentLeftByteData;
	/** the starting index of the current arrays */
	private int currentRightIndex, currentLeftIndex;
	/** the thread which plays the sound through the audiotracks */
	private Thread leftStartThread, rightStartThread, leftUpdateThread, rightUpdateThread;
	/** true if the current ITDTrack has data to play, false otherwise */
	private boolean isAlive, isRightAlive, isLeftAlive;
	/** the AudioTracks for the right and left ears */
	private AudioTrack atRight, atLeft;
	/** the WavInfo for the ITDTrack's original wav file */
	private WavInfo info;
	/** the right and left volumes */
	private float rightVol, leftVol;
	/** the angle in radians of the sound source from the positive x axis (counterclockwise) in portrait */
	private double angle;
	/** the start time for the sound in system time */
	private static long oTime;
	/** true if the track has been paused, false otherwise */
	private boolean paused;
	/** true if the current starting index of the buffer is updatable, false otherwise */
	private boolean isUpdatable;
	/** the old increases */
	private int oRightIncrease, oLeftIncrease;
	/** true if the track should be looped, false otherwise (default false) */
	private boolean isLooped;
	/** the max ITD in seconds */
	private static double MAX_ITD = 0.0006;
	
	//Constructor

	/**
	 * A duo AudioTrack wrapper wav file player which manipulates the input byte arrays for the AudioTracks in realtime while performing playback functions.
	 * @param sourceLocation 	The in file location of the source wav file (e.g R.raw.example)
	 * @param activity  		The activity under which this ITD has been instantiated
	 * @param radians  			The azimuth angle of the sound source relative to the center of the head
	 * @param isLooped 			True if the track should be looped, false otherwise
	 * @throws IOException		If the wav file fails to be compatible with WavReader (e.g. sample rate too low)
	 */
	public ITDTrack (Integer sourceLocation, MainActivity activity, double radians, boolean isLooped)
			throws IOException {
		this.angle = radians;
		this.activity = activity;
		this.currentRightIndex = 0;
		this.currentLeftIndex = 0;
		this.rightVol = 0;
		this.leftVol = 0;
		this.paused = false;
		this.oRightIncrease = 0;
		this.oLeftIncrease = 0;
		this.isLooped = isLooped;

		InputStream audioStream = activity.getResources().openRawResource(sourceLocation);
		try {
			this.info = WavReader.readHeader(audioStream);
			this.shortData = HelperFuncs.bytesToShorts(WavReader.readWavPcm(info, audioStream));
		} catch (AuditorySearch.WavReader.WavException e) {
			e.printStackTrace();
		}

		this.rate = info.rate;
		this.channels = info.channels;
		this.dataSize = info.dataSize;

		// Set and push to audio track..
		int intSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
		atRight = new AudioTrack(AudioManager.STREAM_MUSIC, rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT, intSize, AudioTrack.MODE_STREAM);
		atLeft = new AudioTrack(AudioManager.STREAM_MUSIC, rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT, intSize, AudioTrack.MODE_STREAM);

		//calculations every ms
		this.currentArraySize = intSize;
		this.currentLeftByteData = new short[currentArraySize];
		this.currentRightByteData = new short[currentArraySize];

		isAlive = true;
		isUpdatable = true;

		this.setVolume(1, 1);
	}

	//Movement and Index Manipulation

	/** 
	 * Increases the right byte array index for the start of the next currentRightByteData array (static increment used for playback).
	 */
	public void incrementCurrentRightIndex() {
		this.currentRightIndex = this.currentRightIndex + this.currentArraySize;
	}

	/** 
	 * Increases the left byte array index for the start of the next currentLeftByteData array (static increment used for playback).
	 */
	public void incrementCurrentLeftIndex() {
		this.currentLeftIndex = this.currentLeftIndex + this.currentArraySize;
	}

	/** 
	 * Used to shift the starting index of the next currentRightByteData array (used to create ITD).
	 * @param increase The delayed shift of the index
	 */
	public void shiftCurrentRightIndex(int increase) {
		isUpdatable = false;
		this.currentRightIndex = this.currentRightIndex - this.oRightIncrease;
		this.currentRightIndex = this.currentRightIndex + increase;
		this.oRightIncrease = increase;
		isUpdatable = true;
	}

	/** 
	 * Used to shift the starting index of the next currentLeftByteData array (used to create ITD).
	 * @param increase The delayed shift of the index
	 */
	public void shiftCurrentLeftIndex(int increase) {
		isUpdatable = false;
		this.currentLeftIndex = this.currentLeftIndex - this.oLeftIncrease;
		this.currentLeftIndex = this.currentLeftIndex + increase;
		this.oLeftIncrease = increase;
		isUpdatable = true;
	}

	/**
	 * Called whenever the ITD needs to be changed, changes the ITD based on the angle.
	 */
	public void updateCurrentITD() {
		int maxDeviation = (int) (MAX_ITD * this.rate);
		shiftCurrentRightIndex((int) ((this.angle/(Math.PI/2))*maxDeviation/2));
		shiftCurrentLeftIndex((int) -((this.angle/(Math.PI/2))*maxDeviation/2));
	}

	//Common Commands For Audio Tracks

	/**
	 * Plays the audio file as two Audiotracks, one of the right ear (atRight) and one of the left ear (atLeft).
	 */
	public void play() {
		if (this.paused) {
			this.paused = false;
			isLeftAlive = true;
			isRightAlive = true;
			atRight.play();
			atLeft.play();
		}
		else {
			this.leftStartThread = new Thread () {
				@Override
				public void run() {
					setPriority(Thread.MAX_PRIORITY);
					atLeft.play();
					for (int i = 0; i < currentArraySize; i++) {
						currentLeftByteData[i] = 0;
					}
					atLeft.write(currentLeftByteData, 0, currentArraySize);
				}
			};

			this.rightStartThread = new Thread () {
				@Override
				public void run() {
					setPriority(Thread.MAX_PRIORITY);
					atRight.play();
					for (int i = 0; i < currentArraySize; i++) {
						currentRightByteData[i] = 0;
					}
					atRight.write(currentRightByteData, 0, currentArraySize);
				}
			};

			initLeftThread();
			initRightThread();

			try {
				leftStartThread.join();
				rightStartThread.join();
				isRightAlive = true;
				isLeftAlive = true;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			initUpdateThread();
			oTime = System.currentTimeMillis();
		}
	}

	/**
	 * Pauses both left and right ear AudioTracks.
	 */
	public void pause() {
		if (isAlive) {
			isLeftAlive = false;
			isRightAlive = false;
			atRight.pause();
			atLeft.pause();
			this.paused = true;
		}
	}

	/**
	 * Stops both left and right ear AudioTracks.
	 */
	public void stop() {
		if (isRightAlive && isLeftAlive) {
			isLeftAlive = false;
			isRightAlive = false;
			atRight.stop();
			atLeft.stop();
			isAlive = false;
		}
	}

	/**
	 * Releases both left and right ear AudioTracks.
	 */
	public void release() {
		isUpdatable = false;
		isLeftAlive = false;
		isRightAlive = false;
		atRight.release();
		atLeft.release();
		isAlive = false;
	}

	/**
	 * Gets the current position of the track, calculated by subtracting the system time when the playback started from the current system time.
	 * @return The amount of time passed in ms since the ITDTrack has started playing
	 */
	public int getCurrentPosition() {
		if (isAlive) {
			return HelperFuncs.safeLongToInt(System.currentTimeMillis() - oTime);
		}
		else return 0;
	}

	/**
	 * Gets the duration of the track, calculated using the length of the sound data array and the sampling rate.
	 * @return The duration of the wav file in ms
	 */

	public int getDuration() {
		double timeInSec = (double) (this.dataSize)/this.rate;
		return (int) (timeInSec*1000);
	}

	//Getters and Setters
	
	/**
	 * Gets the number of channels of the wav file.
	 * @return The number of channels in this wav file
	 */
	public int getChannels() {
		return this.channels;
	}

	/**
	 * Gets the sample rate of the wav file.
	 * @return The rate of this particular wav file
	 */
	public int getRate() {
		return this.rate;
	}

	/**
	 * Gets the size of the wav file in terms of number of shorts.
	 * @return The size of the wav file in terms of number of shorts
	 */
	public int getDataSize() {
		return this.dataSize;
	}

	/**
	 * Gets the short array of data from the wav file.
	 * @return The short array of the audio stream data from this wav file
	 */
	public short[] getShortData() {
		return this.shortData;
	}

	/**
	 * Sets the volume of the track which plays into the right ear.
	 * @param vol Float between 0 and 1 for the volume
	 */
	public void setRightVolume (float vol) {
		this.rightVol = vol;
		atRight.setStereoVolume(0, this.rightVol);
	}

	/**
	 * Sets the volume of the track which plays into the left ear.
	 * @param vol Float between 0 and 1 for the volume
	 */
	public void setLeftVolume (float vol) {
		this.leftVol = vol;
		atLeft.setStereoVolume(this.leftVol, 0);
	}

	/**
	 * Sets the angle at which the ITDTrack representation on screen is from the Y axis (the smaller angle).
	 * @param angle Smaller angle from the on screen representation of the ITDTrack to the Y axis in radians (< 0 if to the left of the y-axis, > 0 if to the right of the y-axis), useful with HelperFuncs.angleFromYAxis 
	 */
	public void setAngle (double angle) {
		this.angle = angle;
	}

	/** 
	 * Sets the volume of both the speakers.
	 * @param lVol Float between 0 and 1 of the volume for the left ear track
	 * @param rVol Float between 0 and 1 of the volume for the right ear track
	 */
	public void setVolume (float lVol, float rVol) {
		this.leftVol = lVol;
		this.rightVol = rVol;
		atRight.setStereoVolume(0, rightVol);
		atLeft.setStereoVolume(leftVol, 0);
	}

	//Threads

	/**
	 * Starts the AudioTrack in the left ear and its associated buffer array.
	 */
	public void initLeftThread() {
		leftStartThread.start();
	}

	/**
	 * Starts the AudioTrack in the right ear and its associated buffer array.
	 */
	public void initRightThread() {
		rightStartThread.start();
	}

	/**
	 * Starts the threads which constantly update the current index and actually steps through the wav file to play the whole wav file.
	 */
	public void initUpdateThread() {

		this.leftUpdateThread = new Thread () {
			@Override
			public void run() {
				setPriority(Thread.MAX_PRIORITY);
				while (isLeftAlive) {
					//soft thread safety loop
					while (isUpdatable) {
						//assigns the currentLeftByteData array with the left audio data based on its current index
						for (int i = 0; i < currentArraySize; i++) {
							if (currentLeftIndex + i < 0) {
								currentLeftByteData[i] = 0;
							}
							else if (currentLeftIndex + i >= shortData.length) {
								if (isLooped) {
									currentLeftIndex = currentLeftIndex - shortData.length;
								}
								else {
									currentLeftByteData[i] = 0;
								}
							}
							else {
								currentLeftByteData[i] = shortData[currentLeftIndex + i];
							}
						}
						//increment the current left index
						incrementCurrentLeftIndex();
						// Write the byte array to the track
						atLeft.write(currentLeftByteData, 0, currentArraySize);
					}
				}
			}
		};

		this.rightUpdateThread = new Thread () {
			@Override
			public void run() {
				setPriority(Thread.MAX_PRIORITY);
				while (isRightAlive) {
					//soft thread safety loop
					while (isUpdatable) {
						//assigns the currentRightByteData array with the right audio data based on its current index
						for (int i = 0; i < currentArraySize; i++) {
							if (currentRightIndex + i < 0) {
								currentRightByteData[i] = 0;
							}
							else if (currentRightIndex + i >= shortData.length) {
								if (isLooped) {
									currentRightIndex = currentRightIndex - shortData.length;
								}
								else {
									currentRightByteData[i] = 0;
								}
							}
							else {
								currentRightByteData[i] = shortData[currentRightIndex + i];
							}
						}
						//increment the current right index
						incrementCurrentRightIndex();
						// Write the byte array to the track
						atRight.write(currentRightByteData, 0, currentArraySize);
					}
				}
			}
		};

		rightUpdateThread.start();
		leftUpdateThread.start();
	}
}

