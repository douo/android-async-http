/*
    Android Asynchronous Http Client
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.loopj.android.http;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;

import android.util.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

class AsyncHttpRequest implements Runnable {
    private final AbstractHttpClient client;
    private final HttpContext context;
    private final HttpUriRequest request;
    private final AsyncHttpResponseHandler responseHandler;
    private boolean isBinaryRequest;
    private int executionCount;
    private boolean cancel;
    private static final String TAG = "AsyncHttpRequest";
    public AsyncHttpRequest(AbstractHttpClient client, HttpContext context, HttpUriRequest request, AsyncHttpResponseHandler responseHandler) {
        this.client = client;
        this.context = context;
        this.request = request;
        this.responseHandler = responseHandler;
        if (responseHandler instanceof BinaryHttpResponseHandler) {
            this.isBinaryRequest = true;
        }
        this.cancel = false;
    }
    
    public void cancel(){
    	cancel = true;
    	Log.d(TAG,"cancel");
    	if(request !=null&&!request.isAborted()){
    		Log.d(TAG,"abort");
    		request.abort();
    	}
    	responseHandler.sendCancelMessage();
    }
    
    public boolean isCancelled(){    	
    	return cancel;
    }

    @Override
    public void run() {
        try {
            if (responseHandler != null) {
                responseHandler.sendStartMessage();
            }

            makeRequestWithRetries();
            
            if (responseHandler != null) {
                responseHandler.sendFinishMessage();
            }
        } catch (IOException e) {
            if (responseHandler != null) {
                responseHandler.sendFinishMessage();
                if (this.isBinaryRequest) {
                    responseHandler.sendFailureMessage(e, (byte[]) null);
                } else {
                    responseHandler.sendFailureMessage(e, (String) null);
                }
            }
        }
    }

    private void makeRequest() throws IOException {
        if (!isCancelled()) {
            try {
                // Fixes #115
                if (request.getURI().getScheme() == null)
                    throw new MalformedURLException("No valid URI scheme was provided");
                HttpResponse response = client.execute(request, context);
                if (!isCancelled()) {
                    if (responseHandler != null) {
                        responseHandler.sendResponseMessage(response);
                    }
                }
            } catch (IOException e) {
                if (!isCancelled()) {
                    throw e;
                }
            }
        }
    }

    private void makeRequestWithRetries() throws ConnectException {
        // This is an additional layer of retry logic lifted from droid-fu
        // See: https://github.com/kaeppler/droid-fu/blob/master/src/main/java/com/github/droidfu/http/BetterHttpRequestBase.java
        boolean retry = true;
        IOException cause = null;
        HttpRequestRetryHandler retryHandler = client.getHttpRequestRetryHandler();
        while (retry) {
            try {
                makeRequest();
                return;
            } catch (ClientProtocolException e) {
                if (responseHandler != null) {
                    responseHandler.sendFailureMessage(e, "cannot repeat the request");
                }
                return;
            } catch (UnknownHostException e) {
                if (responseHandler != null) {
                    responseHandler.sendFailureMessage(e, "can't resolve host");
                }
                return;
            } catch (ConnectTimeoutException e) {
                if (responseHandler != null) {
                    responseHandler.sendFailureMessage(e, "connection timed out");
                }
            } catch (SocketException e) {
                // Added to detect host unreachable
                if (responseHandler != null) {
                    responseHandler.sendFailureMessage(e, "can't resolve host");
                }
                return;
            } catch (SocketTimeoutException e) {
                if (responseHandler != null) {
                    responseHandler.sendFailureMessage(e, "socket time out");
                }
                return;
            } catch (IOException e) {
            	e.printStackTrace();
                cause = e;                
                retry = retryHandler.retryRequest(cause, ++executionCount, context);
            } catch (NullPointerException e) {
            	e.printStackTrace();
                // there's a bug in HttpClient 4.0.x that on some occasions causes
                // DefaultRequestExecutor to throw an NPE, see
                // http://code.google.com/p/android/issues/detail?id=5255
                cause = new IOException("NPE in HttpClient" + e.getMessage());
                retry = retryHandler.retryRequest(cause, ++executionCount, context);
            }
        }

        // no retries left, crap out with exception
        ConnectException ex = new ConnectException();
        ex.initCause(cause);
        throw ex;
    }
}
