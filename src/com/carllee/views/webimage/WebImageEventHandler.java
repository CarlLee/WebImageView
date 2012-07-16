package com.carllee.views.webimage;

import android.graphics.Bitmap;

public interface WebImageEventHandler {

	public abstract void handleFetchedImage(Bitmap bitmap);

	public abstract void handleFetchImageFailure();
}
