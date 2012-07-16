package com.carllee.views.webimage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

/**
 * 使用内部线程来完成图片下载、缓存和加载的ImageView
 * 
 * @author Carl Lee
 * 
 */
public class WebImageView extends ImageView implements WebImageEventHandler {
	public static final int WAIT_TYPE_PROGRESS_BAR = 0x01;
	public static final int WAIT_TYPE_IMAGE = 0x02;
	public static final int WAIT_TYPE_BLANK = 0x04;
	public static final int STATUS_WAITING = 0x01;
	public static final int STATUS_NORMAL = 0x02;
	public static final int STATUS_ERROR = 0x04;

	private static final boolean DEBUG = true;
	private static final String TAG = "WebImageView";
	private static HttpClient mHttpClient;
	private static Map<String, ImageDownloadThread> mThreads = new ConcurrentHashMap<String, ImageDownloadThread>();
	private static Map<String, SoftReference<Bitmap>> mCachedBitmaps = new ConcurrentHashMap<String, SoftReference<Bitmap>>();
	private static ExecutorService mExecutor = Executors.newFixedThreadPool(5);

	private String mImageURL;
	private String mWaitingImageURL;
	private WebImageEventHandler mWaitingImageEventHandler;
	private String mErrorImageURL;
	private WebImageEventHandler mErrorImageEventHandler;
	private ArrayList<WebImageEventHandler> mHandlers = new ArrayList<WebImageEventHandler>();

	private String mCacheDirPath;
	private Drawable mWaitImageDrawable;
	private Drawable mErrorImageDrawable;
	private Handler mMainThreadHandler;

	private int mWaitingType = WAIT_TYPE_IMAGE;
	private int mCurrentStatus = STATUS_NORMAL;

	public WebImageView(Context context) {
		this(context, null);
	}

	public WebImageView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public WebImageView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		File cacheDir = new File(context.getCacheDir().getAbsolutePath()
				+ "/web_images");
		if (!cacheDir.exists()) {
			cacheDir.mkdirs();
		}
		mCacheDirPath = cacheDir.getAbsolutePath();

		if (mHttpClient == null) {
			HttpParams params = new BasicHttpParams();
			HttpConnectionParams.setConnectionTimeout(params, 10 * 1000);
			HttpConnectionParams.setSoTimeout(params, 10 * 1000);
			HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);

			// Create and initialize scheme registry
			SchemeRegistry schemeRegistry = new SchemeRegistry();
			schemeRegistry.register(new Scheme("http", PlainSocketFactory
					.getSocketFactory(), 80));

			// Create an HttpClient with the ThreadSafeClientConnManager.
			// This connection manager must be used if more than one thread will
			// be using the HttpClient.
			ClientConnectionManager cm = new ThreadSafeClientConnManager(
					params, schemeRegistry);

			mHttpClient = new DefaultHttpClient(cm, params);
		}

		mMainThreadHandler = new Handler(Looper.getMainLooper());

		// setImageURL(imageURL);
	}

	/**
	 * 设置该ImageView的图像对应的URL
	 * 
	 * @param imageURL
	 */
	public void setImageURL(String imageURL) {
		if (DEBUG)
			Log.d(TAG, WebImageView.this + ": " + "setImageURL("
					+ getImageFileName(imageURL) + ") mCurStatus="
					+ mCurrentStatus);
		// if (mCurrentStatus == STATUS_WAITING) {
		if (mImageURL != null) {
			// 如果此时正在等待某线程的消息 则取消等待该线程
			ImageDownloadThread tmpThd = mThreads.get(mImageURL);
			dumpThreads();
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": "
						+ "Retrived previous thread(" + tmpThd + ", "
						+ mImageURL + ")");
			if (tmpThd != null) {
				tmpThd.removeHandler(this);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Removed handler from " + tmpThd + " for image "
							+ getImageFileName(mImageURL));
			}
		}
		this.mImageURL = imageURL;
		// 检查内存中的缓存
		{
			Bitmap bm = getFromMemCache(imageURL);
			if (bm != null) {
				setImageBitmap(bm);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": " + "Get image("
							+ getImageFileName(imageURL) + ", " + bm
							+ ") from cache");
				callAllHandlersSuccesses(bm);
				return;
			}
		}
		// 内存中没有缓存则查找磁盘上的缓存
		File cacheFile = getCacheFile(imageURL);
		if (cacheFile.exists()) {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "Using cached file for "
						+ getImageFileName(imageURL) + "...");
			final Bitmap bm = BitmapFactory.decodeFile(cacheFile
					.getAbsolutePath());
			putIntoMemCache(imageURL, bm);
			setImageBitmap(bm);
			setCurrentStatus(STATUS_NORMAL);

			callAllHandlersSuccesses(bm);
		} else {
			// 磁盘上没有缓存则查看线程池中有无已有线程在下载
			if (DEBUG)
				Log.d(TAG,
						WebImageView.this + ": " + "File "
								+ cacheFile.getName() + " doesn't exist..");
			setCurrentStatus(STATUS_WAITING);
			ImageDownloadThread thd = mThreads.get(imageURL);
			dumpThreads();
			if (thd == null) {
				// 没有线程正在下载该图片则开启新线程
				thd = new ImageDownloadThread(imageURL, this);
				mExecutor.execute(thd);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": " + "Downloading for "
							+ getImageFileName(imageURL) + " with " + thd);
				mThreads.put(imageURL, thd);
				dumpThreads();
			} else {
				// 有线程在下载则不开启新线程，转为注册监听器
				thd.addHandler(this);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": " + "Added handler for"
							+ getImageFileName(imageURL)
							+ " to existing thread" + thd + "...");
			}
		}
	}

	public String getImageURL() {
		return this.mImageURL;
	}

	public void setWaitingType(int type) {
		this.mWaitingType = type;
	}

	public void setWaitingImageURL(String imageURL) {
		if (mWaitingImageURL != null) {
			ImageDownloadThread tmpThd = mThreads.get(mWaitingImageURL);
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": "
						+ "Retrived previous thread(" + tmpThd + ", "
						+ mImageURL + ") for waiting image");
			if (tmpThd != null) {
				tmpThd.removeHandler(mWaitingImageEventHandler);
				dumpThreads();
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Removed handler from " + tmpThd
							+ " for waiting image "
							+ getImageFileName(mWaitingImageURL));
			}
		}
		{
			Bitmap bm = getFromMemCache(imageURL);
			if (bm != null) {
				setWaitingImageBitmap(bm);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": " + "Get image ("
							+ getImageFileName(imageURL) + "," + bm
							+ ") from cache for waiting image");
				return;
			}
		}

		File cacheFile = getCacheFile(imageURL);
		if (cacheFile.exists()) {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": "
						+ "Using cached file for waiting image"
						+ getImageFileName(imageURL) + "...");
			Bitmap bm = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
			setWaitingImageBitmap(bm);
			putIntoMemCache(imageURL, bm);
		} else {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "File for waiting image "
						+ cacheFile.getAbsolutePath() + " doesn't exist..");
			ImageDownloadThread thd = mThreads.get(imageURL);
			final String tmpImageURL = imageURL;
			if (mWaitingImageEventHandler == null) {
				mWaitingImageEventHandler = new WebImageEventHandler() {

					@Override
					public void handleFetchedImage(Bitmap bitmap) {
						setWaitingImageBitmap(bitmap);
						mThreads.remove(tmpImageURL);
					}

					@Override
					public void handleFetchImageFailure() {
						mThreads.remove(tmpImageURL);
					}
				};
			}
			if (thd == null) {
				thd = new ImageDownloadThread(imageURL,
						mWaitingImageEventHandler);
				mExecutor.execute(thd);
				mThreads.put(imageURL, thd);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Downloading wait image "
							+ getImageFileName(imageURL) + " with " + thd);

			} else {
				thd.addHandler(mWaitingImageEventHandler);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Added handler for waiting image "
							+ getImageFileName(imageURL)
							+ " to existing thread" + thd + "...");
			}
		}
	}

	public void setWaitingImageBitmap(Bitmap bitmap) {
		setWaitingImageDrawable(new BitmapDrawable(bitmap));
	}

	public void setWaitingImageDrawable(Drawable drawable) {
		mWaitImageDrawable = drawable;
	}

	public void setErrorImageURL(String imageURL) {
		if (mErrorImageEventHandler != null) {
			ImageDownloadThread tmpThd = mThreads.get(mErrorImageURL);
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": "
						+ "Retrived previous thread(" + tmpThd + ", "
						+ mImageURL + ") for error image");
			if (tmpThd != null) {
				tmpThd.removeHandler(mErrorImageEventHandler);
				dumpThreads();
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Removed handler from " + tmpThd
							+ " for error image "
							+ getImageFileName(mErrorImageURL));
			}
		}
		{
			Bitmap bm = getFromMemCache(imageURL);
			if (bm != null) {
				setErrorImageBitmap(bm);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": " + "Get image ("
							+ getImageFileName(imageURL) + "," + bm
							+ ") from cache for error image");
				return;
			}
		}

		File cacheFile = getCacheFile(imageURL);
		if (cacheFile.exists()) {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": "
						+ "Using cached file for error image"
						+ getImageFileName(imageURL) + "...");
			Bitmap bm = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
			setErrorImageBitmap(bm);
			putIntoMemCache(imageURL, bm);
		} else {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "File for error image "
						+ cacheFile.getAbsolutePath() + " doesn't exist..");
			ImageDownloadThread thd = mThreads.get(imageURL);
			final String tmpImageURL = imageURL;
			if (mErrorImageEventHandler == null) {
				mErrorImageEventHandler = new WebImageEventHandler() {

					@Override
					public void handleFetchedImage(Bitmap bitmap) {
						setErrorImageBitmap(bitmap);
						mThreads.remove(tmpImageURL);
					}

					@Override
					public void handleFetchImageFailure() {
						mThreads.remove(tmpImageURL);
					}
				};
			}
			if (thd == null) {
				thd = new ImageDownloadThread(imageURL, mErrorImageEventHandler);
				mExecutor.execute(thd);
				mThreads.put(imageURL, thd);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Downloading error image "
							+ getImageFileName(imageURL) + " with " + thd);

			} else {
				thd.addHandler(mWaitingImageEventHandler);
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Added handler for error image "
							+ getImageFileName(imageURL)
							+ " to existing thread" + thd + "...");
			}
		}
	}

	public void setErrorImageBitmap(Bitmap bitmap) {
		setErrorImageDrawable(new BitmapDrawable(bitmap));
	}

	public void setErrorImageDrawable(Drawable drawable) {
		mErrorImageDrawable = drawable;
	}

	/**
	 * 添加监听网络图片被下载或者下载出错事件的监听器； 所有的handler都会被在主线程中被调用
	 * 
	 * @param handler
	 */
	public void addWebImageEventHandler(WebImageEventHandler handler) {
		mHandlers.add(handler);
	}

	private void setCurrentStatus(int status) {
		if (DEBUG)
			Log.d(TAG, WebImageView.this + ": " + "setCurrentStatus(" + status
					+ ")");
		mCurrentStatus = status;
		switch (status) {
		case STATUS_ERROR: {
			setImageDrawable(mErrorImageDrawable);
			break;
		}
		case STATUS_WAITING: {
			setImageDrawable(mWaitImageDrawable);
			break;
		}
		default: {
			break;
		}
		}
	}

	private File getCacheFile(String imageURL) {
		return new File(mCacheDirPath + "/" + getImageFileName(imageURL));
	}

	private String getImageFileName(String imageURL) {
		int lastIndex = imageURL.lastIndexOf('/');

		if (lastIndex < 0) {
			return imageURL;
		} else {
			return imageURL.substring(lastIndex + 1, imageURL.length());
		}
	}

	private FileOutputStream openCacheFileOutputStream(String imageURL) {
		try {
			return new FileOutputStream(getCacheFile(imageURL));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "File not found: "
						+ getCacheFile(imageURL));
			return null;
		}
	}

	private void putIntoMemCache(String imageURL, Bitmap bm) {
		if (!isInMemCache(imageURL)) {
			mCachedBitmaps.put(imageURL, new SoftReference<Bitmap>(bm));
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "Put image("
						+ getImageFileName(imageURL) + ", " + bm
						+ ") into cache");
		}
	}

	private Bitmap getFromMemCache(String imageURL) {
		Bitmap bm = null;
		SoftReference<Bitmap> bmsr = mCachedBitmaps.get(imageURL);
		if (bmsr != null) {
			bm = bmsr.get();
		}
		return bm;
	}

	private boolean isInMemCache(String imageURL) {
		boolean r = false;
		SoftReference<Bitmap> bmsr = mCachedBitmaps.get(imageURL);
		if (bmsr != null) {
			Bitmap bm = bmsr.get();
			if (bm != null) {
				r = true;
			}
		}
		return r;
	}

	private void callAllHandlersSuccesses(final Bitmap bitmap) {
		mMainThreadHandler.post(new Runnable() {

			@Override
			public void run() {
				for (WebImageEventHandler handler : mHandlers) {
					handler.handleFetchedImage(bitmap);
				}
			}
		});
	}

	private void callAllHandlersFailures() {
		mMainThreadHandler.post(new Runnable() {

			@Override
			public void run() {
				for (WebImageEventHandler handler : mHandlers) {
					handler.handleFetchImageFailure();
				}
			}
		});

	}

	private void dumpThreads() {
		Log.d(TAG, WebImageView.this
				+ ": ------------------Dumping Threads--------------");
		for (Entry<String, ImageDownloadThread> entry : mThreads.entrySet()) {
			Log.d(TAG, WebImageView.this + ": " + entry.getKey() + " = "
					+ entry.getValue());
		}
		Log.d(TAG, WebImageView.this
				+ ": ------------------Dumping Ended--------------");
	}

	@Override
	public void handleFetchedImage(final Bitmap bitmap) {
		mMainThreadHandler.post(new Runnable() {

			@Override
			public void run() {
				setImageBitmap(bitmap);
				setCurrentStatus(STATUS_NORMAL);
			}
		});
		callAllHandlersSuccesses(bitmap);
	}

	@Override
	public void handleFetchImageFailure() {
		mMainThreadHandler.post(new Runnable() {

			@Override
			public void run() {
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "handleFetchImageFailure ("
							+ getImageFileName(mImageURL) + ")");
				setCurrentStatus(STATUS_ERROR);
			}
		});
		callAllHandlersFailures();
	}

	private class ImageDownloadThread implements Runnable {
		private String imageURL;
		private ArrayList<WebImageEventHandler> handlers = new ArrayList<WebImageEventHandler>();

		public ImageDownloadThread(String imageURL, WebImageEventHandler handler) {
			this.imageURL = imageURL;
			addHandler(handler);
		}

		/**
		 * 添加一个监听网络图片相关事件的监听器
		 * 
		 * @param handler
		 */
		public void addHandler(WebImageEventHandler handler) {
			if (handler != null)
				handlers.add(handler);
		}

		/**
		 * 删除一个监听网络图片相关事件的监听器
		 * 
		 * @param handler
		 */
		public void removeHandler(WebImageEventHandler handler) {
			handlers.remove(handler);
		}

		public String getDebugInfo() {
			return "[" + getImageFileName(imageURL) + ", " + this + ", "
					+ handlers.size() + "]";
		}

		public void callHandlerFailures() {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "callHandlerFailures "
						+ getDebugInfo());
			mThreads.remove(imageURL);
			for (WebImageEventHandler handler : handlers) {
				handler.handleFetchImageFailure();
			}
		}

		public void callHandlerSuccesses(Bitmap bitmap) {
			if (DEBUG)
				Log.d(TAG, WebImageView.this + ": " + "callHandlerSuccesses "
						+ getDebugInfo());
			mThreads.remove(imageURL);
			for (WebImageEventHandler handler : handlers) {
				handler.handleFetchedImage(bitmap);
			}
		}

		@Override
		public void run() {
			InputStream is = null;
			OutputStream os = null;
			ByteArrayOutputStream baos = null;
			try {
				HttpGet req = new HttpGet(imageURL);
				HttpResponse resp = mHttpClient.execute(req);
				if (!(resp.getStatusLine().getStatusCode() == 200)) {
					throw new IOException("HTTP response is abnormal...");
				}
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Connection established for " + getDebugInfo());
				is = resp.getEntity().getContent();
				os = openCacheFileOutputStream(imageURL);
				baos = new ByteArrayOutputStream();
				if (is == null || os == null) {
					throw new IOException(
							"Cannot read or write data for image...");
				}

				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Starting to download " + getDebugInfo());
				int length = 0;
				byte[] buffer = new byte[1024];
				while ((length = is.read(buffer)) > 0) {
					os.write(buffer, 0, length);
					baos.write(buffer, 0, length);
				}
				os.flush();
				baos.flush();
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "Download finished for " + getDebugInfo());

				byte[] bmBuffer = baos.toByteArray();
				final Bitmap bm = BitmapFactory.decodeByteArray(bmBuffer, 0,
						bmBuffer.length);
				bmBuffer = null;
				if (bm == null) {
					// bm == null表示该数据可能为空或者为非图像的信息
					callHandlerFailures();
					throw new IOException("Cannot decode image...");
				} else {
					// 图像解析成功 将图像添加到缓存并处理图像数据
					// 从线程集合中去掉自己
					putIntoMemCache(imageURL, bm);
					callHandlerSuccesses(bm);
				}

			} catch (IOException e) {
				if (DEBUG)
					Log.d(TAG, WebImageView.this + ": "
							+ "IOException occurred for " + getDebugInfo());
				e.printStackTrace();
				callHandlerFailures();
			} finally {
				try {
					if (is != null) {
						is.close();
					}
					if (os != null) {
						os.close();
					}
					if (baos != null) {
						baos.close();
					}
				} catch (IOException e) {
					if (DEBUG)
						Log.d(TAG,
								WebImageView.this
										+ ": "
										+ "IOException occurred while closing streams...");
					e.printStackTrace();
				}
			}
		}
	}
}
