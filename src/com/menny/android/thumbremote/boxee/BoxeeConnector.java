package com.menny.android.thumbremote.boxee;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import com.menny.android.thumbremote.ServerAddress;
import com.menny.android.thumbremote.ServerConnector;
import com.menny.android.thumbremote.UiView;
import com.menny.android.thumbremote.network.HttpRequestBlocking;

/*
 * ftp://ohnopublishing.net/distfiles/svn-src/xbmc/trunk/xbmc/lib/libGoAhead/XBMChttp.cpp
 * http://wiki.xbmc.org/index.php?title=Web_Server_HTTP_API
 */
public class BoxeeConnector implements ServerConnector  {
	public static final String BOXEE_SERVER_TYPE = "Boxee";
	public static final String BOXEE_SERVER_VERSION_OLD = "0.9";
	public static final String BOXEE_SERVER_VERSION_NEW = "1.1";
	
	private static final String TAG = "BoxeeConnector";
	
	private final Queue<String[]> mUrlsToDo = new LinkedList<String[]>();
	
	//various heavily used strings
	private String mRequestPrefix = null;
	private String mRequestPausePlay = null;
	private String mRequestStop = null;
	private String mSendKeyRequestTemplate = null;
	private String mSeekPercentageRelativeTemplate = null;
	private String[] mCurrentPlayingUrls = null;
	private String mRequestUp = null;
	private String mRequestDown = null;
	private String mRequestLeft = null;
	private String mRequestRight = null;
	private String mRequestBack = null;
	private String mRequestSelect = null;
	private String mRequestGetVolume = null;
	private String mRequestSetVolumeTemplate = null;
	
	final static Pattern LIST_ITEM = Pattern.compile("^<li>([A-Za-z ]+):([^\n<]+)", Pattern.MULTILINE);
	final static Pattern SINGLE_ITEM = Pattern.compile("^<li>([0-9]+)", Pattern.MULTILINE);

	private HashMap<String, String> mEntries = new HashMap<String, String>();
	private boolean mInMoreDataState = false;
	private Bitmap mThumbnail;
	
	private UiView mUiView;

	private void clear() {
		synchronized (mEntries) {
			mEntries.clear();			
		}
		mThumbnail = null;
	}
	
	@Override
	public void setUiView(UiView uiView) {
		mUiView = uiView;
	}
	
	@Override
	public void setServer(ServerAddress server) {
		if (server == null)
		{
			mRequestPrefix = null;
			mRequestPausePlay = null;
			mRequestStop = null;
			mSendKeyRequestTemplate = null;
			mCurrentPlayingUrls = null;
			mSeekPercentageRelativeTemplate = null;
			mRequestUp = null;
			mRequestDown = null;
			mRequestLeft = null;
			mRequestRight = null;
			mRequestBack = null;
			mRequestSelect = null;
			mRequestGetVolume = null;
			mRequestSetVolumeTemplate = null;
		}
		else
		{
			//setting up various heavily used strings
			String host = server.address().getHostAddress();
			int port = server.port();
			mRequestPrefix = String.format("http://%s:%d/xbmcCmds/xbmcHttp?command=", host, port);
			mSendKeyRequestTemplate = mRequestPrefix+"SendKey(%d)";
			mRequestUp = String.format(mSendKeyRequestTemplate, 270);
			mRequestDown = String.format(mSendKeyRequestTemplate, 271);
			mRequestLeft = String.format(mSendKeyRequestTemplate, 272);
			mRequestRight = String.format(mSendKeyRequestTemplate, 273);
			mRequestBack = String.format(mSendKeyRequestTemplate, 275);
			mRequestSelect = String.format(mSendKeyRequestTemplate, 256);
			mCurrentPlayingUrls = new String[]{
					mRequestPrefix + "getcurrentlyplaying()",
					mRequestPrefix + "getguistatus()"
			};
			mRequestPausePlay = mRequestPrefix+"Pause";
			mRequestStop = mRequestPrefix+"Stop";
			mSeekPercentageRelativeTemplate = mRequestPrefix + "SeekPercentageRelative(%3.5f)";
			mRequestGetVolume = mRequestPrefix+"getVolume()";
			mRequestSetVolumeTemplate = mRequestPrefix+"setVolume(%d)";
			synchronized (mEntries) {
				mEntries.clear();
			}
		}
	}
	
	@Override
	public void startOver() {
		mInMoreDataState = false;
		mUrlsToDo.clear();
		if (mCurrentPlayingUrls != null)
			mUrlsToDo.add(mCurrentPlayingUrls);
	}

	@Override
	public boolean requiresServerStateData() {
		return mUrlsToDo.size() > 0;
	}

	@Override
	public String[] getServerStateUrls() {
		return mUrlsToDo.poll();
	}

	@Override
	public void onServerStateResponsesAvailable(String[] responses) {
		synchronized (mEntries) {
			if (mInMoreDataState)
			{
				mInMoreDataState = false;
				String bitmapResponse = responses[0];
				String shorter = bitmapResponse.replaceAll("<html>", "").replaceAll("</html>", "");
				byte[] thumb;
				try {
					thumb = iharder.base64.Base64.decode(shorter.getBytes());
				} catch (IOException e) {
					thumb = null;
					e.printStackTrace();
				}
				
				if (thumb != null)
				{
					mThumbnail = BitmapFactory.decodeByteArray(thumb, 0, thumb.length);
					mUiView.onMetadataChanged(this);
				}
				else
					mThumbnail = null;
			}
			else
			{
				final boolean isPlaying = isMediaPlaying();
				final boolean isMediaActive = isMediaActive();
				final String time = getMediaCurrentTime();
				final String title = getMediaTitle();
				clear();
				if (responses != null)//it can be null if there are lots of network errors.
				{
					for(String response : responses)
					{
						Matcher m = LIST_ITEM.matcher(response);
						while (m.find()) {
							mEntries.put(m.group(1), m.group(2));
						}
					}
				}
				/*
				* void onPlayingStateChanged(ServerState serverState);
				* void onPlayingProgressChanged(ServerState serverState);
				* void onMetadataChanged(ServerState serverState);
				 */
				if (isPlaying != isMediaPlaying() || isMediaActive != isMediaActive())
					mUiView.onPlayingStateChanged(this);
				if (!time.equals(getMediaCurrentTime()))
					mUiView.onPlayingProgressChanged(this);
				if (!title.equals(getMediaTitle()))
				{
					String thumbUrl = getEntryValue("Thumb");
					if (!TextUtils.isEmpty(thumbUrl))
					{
						mInMoreDataState = true;
						mUrlsToDo.add(new String[]{mRequestPrefix + String.format("getthumbnail(%s)", URLEncoder.encode(thumbUrl))});
					}	
				}
			}
		}
	}
	
	@Override
	public void onServerStateRetrievalError() {
		clear();
		startOver();
	}

	private String getEntryValue(String key) {
		synchronized (mEntries) {
			return mEntries.containsKey(key) ? mEntries.get(key) : "";
		}
	}

	/*Media information*/
	
	@Override
	public String getMediaTitle() {
		synchronized (mEntries) {
			String showTitle = mEntries.get("Show Title");
			String title = mEntries.get("Title");
			String filename = mEntries.get("Filename");
	
			if (!TextUtils.isEmpty(showTitle) && !TextUtils.isEmpty(title))
				return showTitle + " - " + title;
	
			if (!TextUtils.isEmpty(title))
				return title;
	
			if (!TextUtils.isEmpty(filename))
				return filename;
	
			return "";
		}
	}

	
	@Override
	public String getMediaTotalTime() {
		return getEntryValue("Duration");
	}

	@Override
	public String getMediaCurrentTime() {
		return getEntryValue("Time");
	}

	@Override
	public int getMediaProgressPercent() {
		String p = getEntryValue("Percentage");
		if (!TextUtils.isEmpty(p) && TextUtils.isDigitsOnly(p))
			return Integer.parseInt(p);
		else
			return 0;
	}

	@Override
	public Bitmap getMediaPoster() {
		return mThumbnail;
	}

	@Override
	public boolean isMediaPlaying() {
		return getEntryValue("PlayStatus").equals("Playing");
	}
	
	@Override
	public boolean isMediaActive() {
		synchronized (mEntries) {
			return mEntries.containsKey("Time");
		}
	}
	
	/*CONTROL*/

	private HttpRequestBlocking.Response sendHttpCommand(final String request) throws IOException {
		HttpRequestBlocking.Response response = HttpRequestBlocking.getHttpResponse(request);
		if (!response.success())
		{
			throw new IOException("Problem fetching URL " + request);
		}
		return response;
	}
	
	private void sendKeyPress(int keycode) throws IOException  {
		String request = String.format(mSendKeyRequestTemplate, keycode);

		sendHttpCommand(request);
	}
	
	@Override
	public int getVolume() throws IOException {
		HttpRequestBlocking.Response response = sendHttpCommand(mRequestGetVolume);
		Matcher m = SINGLE_ITEM.matcher(response.response());
		if (m != null && m.find()) {
			final int currentVolume = Integer.parseInt(m.group(1));
			return currentVolume;
		}
		else
		{
			throw new IOException("Failed to understand server response!");
		}
	}
	
	@Override
	public void setVolume(final int percent) throws IOException {
		int newVolume = Math.max(0, Math.min(100, percent));
		Log.d(TAG, "Setting volume to " + newVolume);
		final String setvolume = String.format(mRequestSetVolumeTemplate, newVolume);
		sendHttpCommand(setvolume);
	}
	
	@Override
	public void up() throws IOException {
		sendHttpCommand(mRequestUp);
	}

	@Override
	public void down() throws IOException {
		sendHttpCommand(mRequestDown);
	}

	@Override
	public void left() throws IOException {
		sendHttpCommand(mRequestLeft);
	}

	@Override
	public void right() throws IOException {
		sendHttpCommand(mRequestRight);
	}

	@Override
	public void back() throws IOException {
		sendHttpCommand(mRequestBack);
	}

	@Override
	public void select() throws IOException {
		sendHttpCommand(mRequestSelect);
	}

	@Override
	public void keypress(int unicode) throws IOException {
		if (unicode == 8)//backspace is a special case
			sendKeyPress(61704);
		else
			sendKeyPress(unicode + 0xF100);
	}

	@Override
	public void flipPlayPause() throws IOException {
		sendHttpCommand(mRequestPausePlay);
	}

	@Override
	public void stop() throws IOException {
		sendHttpCommand(mRequestStop);
	}

	@Override
	public void seekRelative(double pct) throws IOException {
		String request = String.format(mSeekPercentageRelativeTemplate, pct);

		sendHttpCommand(request);
	}

}
