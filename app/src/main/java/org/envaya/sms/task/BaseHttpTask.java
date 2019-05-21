package org.envaya.sms.task;

import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import com.crashlytics.android.Crashlytics;
import com.logentries.logger.AndroidLogger;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.envaya.sms.App;
import org.envaya.sms.JsonUtils;
import org.envaya.sms.R;
import org.envaya.sms.XmlUtils;
import org.envaya.sms.logs.LogentriesAndroidLogger;
import org.json.JSONObject;
import org.w3c.dom.Document;

public class BaseHttpTask extends AsyncTask<String, Void, HttpResponse> {

    protected App app;
    protected String url;    
    protected List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();    

    private List<FormBodyPart> formParts;
    protected boolean useMultipartPost = false;    
    protected HttpPost post;
    protected Throwable requestException;

    private AndroidLogger logger = null;
    
    public BaseHttpTask(App app, String url, BasicNameValuePair... paramsArr)
    {
        this.url = url;
        this.app = app;
        params = new ArrayList<BasicNameValuePair>(Arrays.asList(paramsArr));
        
        params.add(new BasicNameValuePair("version", "" + app.getPackageInfo().versionCode));

        logger = LogentriesAndroidLogger.getInstance(app.getApplicationContext());
    }
    
    public void addParam(String name, String value)
    {
        params.add(new BasicNameValuePair(name, value));
    }    
    
    public void setFormParts(List<FormBodyPart> formParts)
    {
        useMultipartPost = true;
        this.formParts = formParts;
    }                     

    protected HttpPost makeHttpPost() throws Exception
    {
        HttpPost httpPost = new HttpPost(url);
                
        httpPost.setHeader("User-Agent", app.getText(R.string.app_name) + "/" + app.getPackageInfo().versionName + " (Android; SDK "+Build.VERSION.SDK_INT + "; " + Build.MANUFACTURER + "; " + Build.MODEL+")");

        if (useMultipartPost)
        {

            MultipartEntity entity = new MultipartEntity();//HttpMultipartMode.BROWSER_COMPATIBLE);

            Charset charset = Charset.forName("UTF-8");

            for (BasicNameValuePair param : params)
            {
                entity.addPart(param.getName(), new StringBody(param.getValue(), charset));
            }

            for (FormBodyPart formPart : formParts)
            {
                entity.addPart(formPart);
            }
            httpPost.setEntity(entity);
        }
        else
        {
            httpPost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
        }

        logger.log("PARAMS = " + params);
        return httpPost;
    }
    
    protected HttpResponse doInBackground(String... ignored) 
    {
        Log.d("MESSAGE", "MESSAGE = " + params);
        try
        {
            post = makeHttpPost();
            
            HttpClient client = app.getHttpClient();

            return client.execute(post);            
        }     
        catch (Throwable ex) 
        {
            requestException = ex;
            
            try
            {
                String message = ex.getMessage();
                // workaround for https://issues.apache.org/jira/browse/HTTPCLIENT-881
                if ((ex instanceof IOException) 
                        && message != null && message.equals("Connection already shutdown"))
                {
                    // app.log("Retrying request");
                    post = makeHttpPost();
                    HttpClient client = app.getHttpClient();
                    Crashlytics.logException(new Throwable("Connection already shutdown. Message = " + params));
                    
                    return client.execute(post);  
                }
            }
            catch (Throwable ex2)
            {
                requestException = ex2;
                Crashlytics.logException(new Throwable("Exception doOnBackground."
                    + " RequestException = " + requestException + "message params = " + params));
            }            
        }   

        return null;
    }    
           
    protected String getErrorText(HttpResponse response)    
            throws Exception
    {
        String contentType = getContentType(response);
        String error = null;
        
        if (contentType.startsWith("application/json"))
        {
            JSONObject json = JsonUtils.parseResponse(response);
            error = JsonUtils.getErrorText(json);
        }
        else if (contentType.startsWith("text/xml"))
        {
            Document xml = XmlUtils.parseResponse(response);
            error = XmlUtils.getErrorText(xml);
        }
        
        if (error == null)
        {
            error = "HTTP " + response.getStatusLine().getStatusCode();
        }
        Crashlytics.logException(new Throwable("Error. Get error text = " + error +
            "message params = " + params));
        return error;
    }
    
    protected String getContentType(HttpResponse response)
    {
        Header contentTypeHeader = response.getFirstHeader("Content-Type");
        return (contentTypeHeader != null) ? contentTypeHeader.getValue() : "";
    }
    
    @Override
    protected void onPostExecute(HttpResponse response) {
        if (response != null)
        {                
            try
            {
                int statusCode = response.getStatusLine().getStatusCode();                
                
                if (statusCode == 200) 
                {
                    handleResponse(response);
                } 
                else if (statusCode >= 400 && statusCode <= 499)
                {
                    handleErrorResponse(response);
                    handleFailure();
                    Crashlytics.logException(new Throwable("Error onPostExecute status code 400-499. Status code = " + statusCode +
                            ", requestException = " + requestException + "message params = " + params));
                }
                else
                {
                    Crashlytics.logException(new Throwable("Error onPostExecute. Status code = " + statusCode +
                            ", requestException = " + requestException + "message params = " + params));
                    throw new Exception("HTTP " + statusCode);
                }
            }
            catch (Throwable ex)
            {
                post.abort();
                handleResponseException(ex);
                handleFailure();
                Crashlytics.logException(new Throwable("Error onPostExecute. Throwable  = " + ex +
                        ", requestException = " + requestException + "message params = " + params));
            }
            
            try
            {
                response.getEntity().consumeContent();
            }
            catch (IOException ex)
            {
                Crashlytics.logException(new Throwable("Error onPostExecute. IOException  = " + ex.getMessage() +
                        ", requestException = " + requestException + "message params = " + params));
            }
        }
        else
        {
            handleRequestException(requestException);
            handleFailure();
            Crashlytics.logException(new Throwable("Error onPostExecute. RequestException  = " + requestException +
                    "response(null) = " + response + "message params = " + params));
        }
    }
    
    protected void handleResponse(HttpResponse response) throws Exception
    {
    }
    
    protected void handleErrorResponse(HttpResponse response) throws Exception
    {
    }        
    
    protected void handleFailure()
    {
    }            

    protected void handleRequestException(Throwable ex)
    {       
    }

    protected void handleResponseException(Throwable ex)
    {       
    }    
        
}