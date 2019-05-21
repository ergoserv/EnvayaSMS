package org.envaya.sms;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import com.crashlytics.android.Crashlytics;
import com.logentries.logger.AndroidLogger;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import org.envaya.sms.logs.LogentriesAndroidLogger;

public class Inbox {
    private Map<Uri, IncomingMessage> incomingMessages = new HashMap<Uri, IncomingMessage>();
    private App app;
    
    private int numForwardingMessages = 0;
    private Queue<IncomingMessage> incomingQueue = new LinkedList<IncomingMessage>();

    private AndroidLogger logger = null;
    
    public Inbox(App app)
    {
        this.app = app;
    }
    
    public IncomingMessage getMessage(Uri uri)
    {
        return incomingMessages.get(uri);
    }        
    
    public synchronized void forwardMessage(IncomingMessage message) {

        logger = LogentriesAndroidLogger.getInstance(app.getApplicationContext());

        if (message != null) {
            logger.log("Inbox message  = " + message.toString());
        } else {
            logger.log("Inbox message null");
        }

        Uri uri = message.getUri();
        
        if (incomingMessages.containsKey(uri)) {
            app.log("Duplicate incoming "+message.getDisplayType()+", skipping");
            Crashlytics.logException(new Throwable("Duplicate incoming "+message.getDisplayType()+", skipping"));
            return;
        }

        incomingMessages.put(uri, message);
        app.log("Received "+message.getDisplayType());

        
        app.getDatabaseHelper().insertPendingMessage(message);
        
        enqueueMessage(message);
    }                       
    
    public synchronized void enqueueMessage(IncomingMessage message) 
    {
        IncomingMessage.ProcessingState state = message.getProcessingState();
        
        if (state == IncomingMessage.ProcessingState.Scheduled
            || state == IncomingMessage.ProcessingState.None) 
        {
            logger.log("Enequeue inbox message " + message);
            incomingQueue.add(message);
            message.setProcessingState(IncomingMessage.ProcessingState.Queued);
            notifyChanged();
            maybeDequeueMessage();                       
        }
    }    
    
    public synchronized void maybeDequeueMessage()
    {
        if (numForwardingMessages < 2)
        {
            IncomingMessage message = incomingQueue.poll();
            
            if (message == null)
            {
                return;
            }
            
            numForwardingMessages++;
            message.setProcessingState(IncomingMessage.ProcessingState.Forwarding);
            message.tryForwardToServer();
            notifyChanged();
        }          
    }        
    
    public synchronized void deleteMessage(IncomingMessage message)
    {
        incomingMessages.remove(message.getUri());
        
        if (message.getProcessingState() == IncomingMessage.ProcessingState.Queued)
        {
            incomingQueue.remove(message);
        }        
        else if (message.getProcessingState() == IncomingMessage.ProcessingState.Forwarding)
        {
            numForwardingMessages--;
        }        

        app.getDatabaseHelper().deletePendingMessage(message);        
        
        app.log(message.getDescription() + " deleted");
        notifyChanged();
    }    
    
    public synchronized void messageFailed(IncomingMessage message) 
    {        
        message.setProcessingState(IncomingMessage.ProcessingState.None);                
        
        if (message.scheduleRetry())
        {
            message.setProcessingState(IncomingMessage.ProcessingState.Scheduled);
        }
        notifyChanged();
        
        numForwardingMessages--;        
        
        maybeDequeueMessage();

        Crashlytics.logException(new Exception("Inbox message failed " + message.toString()));
    }        
    
    public synchronized void messageForwarded(IncomingMessage message) {
        
        message.setProcessingState(IncomingMessage.ProcessingState.Forwarded);
        
        Uri uri = message.getUri();
        incomingMessages.remove(uri);
            
        notifyChanged();
        
        message.onForwardComplete();
                
        numForwardingMessages--;
        
        app.getDatabaseHelper().deletePendingMessage(message);
        
        maybeDequeueMessage();
    }
    
    private void notifyChanged()
    {
        app.sendBroadcast(new Intent(App.INBOX_CHANGED_INTENT));
    }
    
    public synchronized void retryAll() {
        for (IncomingMessage message : incomingMessages.values()) {            
            enqueueMessage(message);
        }
    }
    
    public synchronized int size() {        
        return incomingMessages.size();
    }  
    
    public synchronized Collection<IncomingMessage> getMessages()
    {
        return incomingMessages.values();
    }    
}