package com.carllee.views.webimage;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.BaseAdapter;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;

public class WebImageViewActivity extends Activity implements
		WebImageEventHandler {
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		ListView lv = new ListView(this);
		lv.setAdapter(new WebImageAdapter());
		setContentView(lv);
		// LinearLayout lv = new LinearLayout(this);
		// WebImageView wiv = new WebImageView(this);
		// wiv.setImageURL("http://fmn.rrimg.com/fmn061/20120712/1200/large_1ikK_3713000002b71190.jpg");
		// lv.addView(wiv, new
		// LinearLayout.LayoutParams(LayoutParams.FILL_PARENT,
		// LayoutParams.WRAP_CONTENT));

		// String url =
		// "http://www.yuwenting.com/wp-content/uploads/2012/06/%d.jpg";
		// WebImageView wiv = new WebImageView(this);
		// wiv.setImageURL(String.format(url, 0));
		// wiv.setImageURL(String.format(url, 1));
		// wiv.setImageURL(String.format(url, 2));
		// wiv.setImageURL(String.format(url, 1));
		// wiv.setImageURL(String.format(url, 0));

	}

	@Override
	public void handleFetchedImage(Bitmap bitmap) {
		Log.d("WebImageView", "WebImageViewHandler called");
	}

	@Override
	public void handleFetchImageFailure() {
		Log.d("WebImageView", "WebImageViewHandler called");
	}

	public class WebImageAdapter extends BaseAdapter {
		String url = "http://www.yuwenting.com/wp-content/uploads/2012/06/%d.jpg";

		@Override
		public int getCount() {
			return 9;
		}

		@Override
		public Object getItem(int position) {
			return null;
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			WebImageView v = null;
			if (convertView != null) {
				if (convertView instanceof WebImageView) {
					v = (WebImageView) convertView;
				}
			} else {
				v = new WebImageView(WebImageViewActivity.this);
				v.setLayoutParams(new ListView.LayoutParams(300, 300));
				final WebImageView tmp = v;
				v.addWebImageEventHandler(new WebImageEventHandler() {

					@Override
					public void handleFetchedImage(Bitmap bitmap) {
						Matrix mx = new Matrix();
						mx.setScale(0.5f, 0.5f);
						tmp.setScaleType(ScaleType.MATRIX);
						tmp.setImageMatrix(mx);
					}

					@Override
					public void handleFetchImageFailure() {
					}
				});
			}
			v.setImageURL(String.format(url, position));
			v.setWaitingImageURL("http://www.allysesmith.com/images/Photos/PleaseWait.gif");
			v.setErrorImageDrawable(getResources()
					.getDrawable(R.drawable.error));
			return v;
		}

	}
}