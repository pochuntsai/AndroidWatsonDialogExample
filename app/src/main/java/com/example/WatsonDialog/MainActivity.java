package com.example.WatsonDialog;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.io.File;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.ibm.watson.developer_cloud.dialog.v1.DialogService;
import com.ibm.watson.developer_cloud.dialog.v1.model.Conversation;
import com.ibm.watson.developer_cloud.dialog.v1.model.Dialog;
import com.ibm.watson.developer_cloud.http.HttpMediaType;
import com.ibm.watson.developer_cloud.speech_to_text.v1.*;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.SpeechResults;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.Transcript;
import com.ibm.watson.developer_cloud.text_to_speech.v1.*;
import com.ibm.watson.developer_cloud.text_to_speech.v1.model.Voice;

import junit.framework.Assert;

import org.apache.commons.io.IOUtils;

public class MainActivity extends Activity {

	private TextView txtInput, txtPrompt, txtOutput;
	private ImageButton btnSpeak;
	private String path="";
	//private String path="/storage/emulated/legacy/Music/input.raw";
	//private String output = "/storage/emulated/legacy/Music/output.wav";
	private String STTResult="";
	private SpeechToText STTService;
	private TextToSpeech TTSService;
	private DialogService DialService;
	private AudioTrack audioTrack;
	AudioRecord recorder = null;
	private String Tag = "Brian";
	private Thread recordingThread;
	private int buffsize;
	private RecognizeOptions RecogOptions = new RecognizeOptions();
	public static final int RECORDING_SAMPLE_RATE = 16000;
	public static final int PLAY_SAMPLE_RATE = 22050; //This value is sample rate of watson TTS output wav file
	public static final String DIALOG_NAME = "liza_example";
	private boolean isRecording = false;
	private List<Dialog> dialogs;
	private Conversation conversation;
	private String conversationResult="";
	private boolean watsonInited = false;
	private String dialogId= "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		txtOutput = (TextView) findViewById(R.id.txtOutput);
		btnSpeak = (ImageButton) findViewById(R.id.btnSpeak);
		txtPrompt = (TextView) findViewById(R.id.txtPrompt);
		txtInput = (TextView) findViewById(R.id.txtInput);

		// hide the action bar
		getActionBar().hide();

		btnSpeak.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				promptSpeechInput();
			}
		});

		//Brian+: Init IBM Watson service
		path = Environment.getExternalStorageDirectory().getAbsolutePath();
		if (path != null) {
			path = path + "/Music/input.raw";
			Log.d("Brian", "path=" + path);
		}
		if (!watsonInited) {
			initWatsonService();
			initRecognizeOptions();
			watsonInited = true;
		}

	}

	private void initWatsonService() {
		STTService = new SpeechToText();
		STTService.setUsernameAndPassword("7663dd81-841f-4da2-92f0-15a3788ccdf3", "RUnuZFq12RVd");
		TTSService = new TextToSpeech();
		TTSService.setUsernameAndPassword("1a58c079-bd99-43a7-b295-f9a0fdf64e35", "vhW3LIOOvqW1");
		DialService = new DialogService();
		DialService.setUsernameAndPassword("26f1aaad-f840-4c21-a89a-a34dbc80029b", "58QMQgmVvpxp");

		new Thread(new Runnable(){
			@Override
			public void run() {
				try {
					//Get Dialogs via DialogService
					dialogs = DialService.getDialogs();
					if (dialogs.size() > 0) {
						for (int i=0; i< dialogs.size(); i++) {
							Log.d(Tag, "Dialog[" + i + "].Name=" + dialogs.get(i).getName());
							Log.d(Tag, "Dialog[" + i + "].ID=" + dialogs.get(i).getId());
							if (dialogs.get(i).getName().equals(DIALOG_NAME)) {
								dialogId = dialogs.get(i).getId();
							}
						}
					}
					else {
						Log.d(Tag, "There is no dialog!!!");
					}
					//Dialog[0]=pizza, Dialog[1]=liza
					//conversation = DialService.createConversation(dialogs.get(1).getId());
					if (!dialogId.equals("")) {
						conversation = DialService.createConversation(dialogId);
						Log.d(Tag, "conversation.response=" + conversation.getResponse().get(0));
						// Send the text to watson TTS service
						InputStream is = TTSService.synthesize(conversation.getResponse().get(0), Voice.EN_ALLISON,	HttpMediaType.AUDIO_WAV);
						Assert.assertNotNull(is);
						byte[] data = analyzeWavData(is);
						initPlayer();
						audioTrack.write(data, 0, data.length);
						is.close();
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				runOnUiThread(new Runnable() {
					public void run() {
						if (!conversation.getResponse().get(0).equals("")) {
							txtOutput.setText(conversation.getResponse().get(0));
						}
					}
				});
			}
		}).start();
	}

	private void initRecognizeOptions() {
		RecogOptions.contentType("audio/l16; rate=" + RECORDING_SAMPLE_RATE + ";");
	}

	/**
	 * Converts Little Endian (Windows) bytes to an int (Java uses Big Endian).
	 * @param data the data to read.
	 * @param offset the offset from which to start reading.
	 * @return the integer value of the reassembled bytes.
	 */
	protected static int readInt(final byte[] data, final int offset)
	{
		return (data[offset] & 0xff) |
				((data[offset+1] & 0xff) <<  8) |
				((data[offset+2] & 0xff) << 16) |
				(data[offset+3] << 24); // no 0xff on the last one to keep the sign
	}

	public byte[] analyzeWavData(InputStream i){
		try {
			int headSize=44, metaDataSize=48, outputSampleRate=0;
			byte[] data = IOUtils.toByteArray(i);
			if(data.length < headSize){
				throw new IOException("Wrong Wav header");
			}

			if(data.length > 28) {
				outputSampleRate = readInt(data, 24); // 24 is the position of sample rate in wav format
				Log.d(Tag, "TTS output wav sample rate=" + outputSampleRate);
			}

			int destPos = headSize + metaDataSize;
			int rawLength = data.length - destPos;

			byte[] d = new byte[rawLength];
			System.arraycopy(data, destPos, d, 0, rawLength);
			return d;
		} catch (IOException e) {
			Log.e("Brian", "Error while formatting header");
		}
		return new byte[0];
	}

	/**
	 * Write input stream to output stream.
	 *
	 * @param inputStream the input stream
	 * @param outputStream the output stream
	 */
	private static void writeInputStreamToOutputStream(InputStream inputStream,
													   OutputStream outputStream) {
		try {
			try {
				final byte[] buffer = new byte[1024];
				int read;

				while ((read = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, read);
				}

				outputStream.flush();
			} catch (final Exception e) {
				e.printStackTrace();
			} finally {
				outputStream.close();
				inputStream.close();
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	private byte[] short2byte(short[] sData) {
		int shortArrsize = sData.length;
		byte[] bytes = new byte[shortArrsize * 2];
		for (int i = 0; i < shortArrsize; i++) {
			bytes[i * 2] = (byte) (sData[i] & 0x00FF);
			bytes[(i * 2) + 1] = (byte) (sData[i] >> 8);
			sData[i] = 0;
		}
		return bytes;

	}

	private void writeAudioDataToFile() {
		// Write the output audio in byte
		short sData[] = new short[buffsize/2];

		FileOutputStream os = null;
		try {
			os = new FileOutputStream(path);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		while (isRecording) {
			// gets the voice output from microphone to byte format

			recorder.read(sData, 0, buffsize/2);
			try {
				// // writes the data to file from buffer
				// // stores the voice buffer
				byte bData[] = short2byte(sData);
				os.write(bData, 0, buffsize);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			os.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void startRecording() {
		buffsize = AudioRecord.getMinBufferSize(RECORDING_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RECORDING_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffsize);
		recorder.startRecording();
		recordingThread = new Thread(new Runnable() {
			public void run() {
				writeAudioDataToFile();
			}
		}, "AudioRecorder Thread");
		recordingThread.start();
		txtPrompt.setText("Tap again to stop recording");
	}

	public void stopRecording() {
		if (null != recorder) {
			isRecording = false;
			recorder.stop();
			recorder.release();
			recorder = null;
			recordingThread = null;
		}
		txtPrompt.setText("Tap MIC to start recording");
	}

	private void testConversation(Conversation c) {
		Assert.assertNotNull(c);
		Assert.assertNotNull(c.getClientId());
		Assert.assertNotNull(c.getId());
		Assert.assertNotNull(c.getResponse());
		Assert.assertFalse(c.getResponse().isEmpty());
	}

	private void promptSpeechInput() {
		if (!isRecording) {
			isRecording = true;
			startRecording();
			if (audioTrack != null && audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
				audioTrack.release();
			}
		}
		else {
			stopRecording();
			isRecording = false;
			new Thread(new Runnable(){
				@Override
				public void run() {
					try {
						//Read record PCM file and send it to Watson TTS service
						File audio = new File(path);
						SpeechResults transcript = STTService.recognize(audio, RecogOptions);
						Log.d(Tag, "STT Result(JSON String) = " + transcript.toString());
						final List<Transcript> result = transcript.getResults();
						STTResult = result.get(0).getAlternatives().get(0).getTranscript();
						Log.d(Tag, "STT Result(String) = " + STTResult);
						runOnUiThread(new Runnable() {
							public void run() {
								if (STTResult.equals("")) {
									txtInput.setText("STT can't recognize your speech!!");

								} else {
									txtInput.setText(STTResult);
								}
							}
						});

						//Send your text to Dialog Service
						conversation = DialService.converse(conversation, STTResult);
						testConversation(conversation);
						Log.d(Tag, "Conversation Result(JSON String) = " + conversation.toString());
						for (int i=0; i< conversation.getResponse().size(); i++) {
							if (!conversation.getResponse().get(i).equals("")) {
								conversationResult = conversation.getResponse().get(i);
								break;
							}
						}

						//Send the text to watson TTS service
						InputStream is;
						if (conversationResult.equals("")) {
							is = TTSService.synthesize(STTResult, Voice.EN_ALLISON, HttpMediaType.AUDIO_WAV);

						} else {
							is = TTSService.synthesize(conversationResult, Voice.EN_ALLISON, HttpMediaType.AUDIO_WAV);
						}
						Assert.assertNotNull(is);
						byte[] data = analyzeWavData(is);
						initPlayer();
						audioTrack.write(data, 0, data.length);
						is.close();
					} catch (Exception ex) {
						ex.printStackTrace();
					}
					runOnUiThread(new Runnable() {
						public void run() {
							if (!conversationResult.equals("")) {
								txtOutput.setText(conversationResult);
							}
							else {
								txtOutput.setText(STTResult);
							}
						}
					});
				}
			}).start();

		}
	}

	private void stopTtsPlayer() {
		if (audioTrack != null && audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED ) {
			// IMPORTANT: NOT use stop()
			// For an immediate stop, use pause(), followed by flush() to discard audio data that hasn't been played back yet.
			audioTrack.pause();
			audioTrack.flush();
			audioTrack.release();
		}
	}

	private void initPlayer(){
		stopTtsPlayer();
		// IMPORTANT: minimum required buffer size for the successful creation of an AudioTrack instance in streaming mode.
		int bufferSize = AudioTrack.getMinBufferSize(PLAY_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
		//bufferSize = bufferSize * 2; //Brian+ for test
		//synchronized (this) {
			audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
					PLAY_SAMPLE_RATE,
					AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					bufferSize,
					AudioTrack.MODE_STREAM);
			if (audioTrack != null) {
				audioTrack.play();
			}
		//}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}


}
