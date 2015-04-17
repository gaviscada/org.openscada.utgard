/*******************************************************************************
 * Copyright (c) 2015 IBH SYSTEMS GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBH SYSTEMS GmbH - initial API and implementation
 *******************************************************************************/
package org.openscada.opc.xmlda;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.namespace.QName;

import org.opcfoundation.webservices.xmlda._1.RequestOptions;
import org.opcfoundation.webservices.xmlda._1.Service;
import org.opcfoundation.webservices.xmlda._1.Subscribe;
import org.opcfoundation.webservices.xmlda._1.SubscribeItemValue;
import org.opcfoundation.webservices.xmlda._1.SubscribePolledRefreshReplyItemList;
import org.opcfoundation.webservices.xmlda._1.SubscribeRequestItem;
import org.opcfoundation.webservices.xmlda._1.SubscribeRequestItemList;
import org.opcfoundation.webservices.xmlda._1.SubscribeResponse;
import org.opcfoundation.webservices.xmlda._1.SubscriptionCancel;
import org.opcfoundation.webservices.xmlda._1.SubscriptionPolledRefresh;
import org.opcfoundation.webservices.xmlda._1.SubscriptionPolledRefreshResponse;
import org.openscada.opc.xmlda.internal.Helper;
import org.openscada.opc.xmlda.requests.ItemValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Poller
{
    private static final AtomicLong POLLER_COUNTER = new AtomicLong ();

    private final static Logger logger = LoggerFactory.getLogger ( Poller.class );

    private final Connection connection;

    private final SubscriptionListener listener;

    private final int waitTime;

    private final String name;

    private final AtomicLong threadCounter = new AtomicLong ();

    private Thread thread;

    private List<ItemRequest> items = new ArrayList<> ();

    private final Map<String, ItemRequest> handleMap = new HashMap<> ();

    private volatile String subscriptionHandle;

    private volatile boolean running;

    private final Executor executor;

    Poller ( final Connection connection, final Executor executor, final SubscriptionListener listener, final int waitTime )
    {
        this.connection = connection;
        this.executor = executor;
        this.listener = listener;
        this.waitTime = waitTime;

        this.name = String.format ( "Poller/%s/%s", connection, POLLER_COUNTER.incrementAndGet () );

        start ();
    }

    protected void setup ()
    {
        final Service soap = this.connection.unwrap ( Service.class );

        final RequestOptions options = makeDefautOptions ();

        final SubscribeRequestItemList itemList = new SubscribeRequestItemList ();

        synchronized ( this )
        {
            if ( this.items.isEmpty () )
            {
                logger.debug ( "No items registered. Skipping ..." );
                return;
            }

            for ( final ItemRequest item : this.items )
            {
                final SubscribeRequestItem itemRequest = new SubscribeRequestItem ();
                itemRequest.setItemName ( item.getItemName () );
                itemRequest.setClientItemHandle ( item.getClientHandle () );
                itemRequest.setEnableBuffering ( true );
                itemRequest.setRequestedSamplingRate ( 100 );
                itemList.getItems ().add ( itemRequest );
            }
        }

        final Subscribe subscribe = new Subscribe ();
        subscribe.setOptions ( options );
        subscribe.setReturnValuesOnReply ( false );
        subscribe.setItemList ( itemList );
        subscribe.setSubscriptionPingRate ( this.waitTime * 4 );

        final SubscribeResponse result = soap.subscribe ( subscribe );

        updateValues ( makeValues ( result ) );

        this.subscriptionHandle = result.getServerSubHandle ();
        fireStateUpdate ( SubscriptionState.ACTIVE );
    }

    public synchronized void setItems ( final List<ItemRequest> items )
    {
        this.subscriptionHandle = null;
        fireStateUpdate ( SubscriptionState.INACTIVE );

        this.items = new ArrayList<> ( items );

        this.handleMap.clear ();
        for ( final ItemRequest req : items )
        {
            this.handleMap.put ( req.getClientHandle (), req );
        }

        notifyAll ();
    }

    public synchronized void setItems ( final String... items )
    {
        setItems ( makeRequests ( Arrays.asList ( items ) ) );
    }

    protected void performPollOnce ()
    {
        synchronized ( this )
        {
            if ( this.subscriptionHandle == null )
            {
                return;
            }
        }

        final Service soap = this.connection.unwrap ( Service.class );

        final RequestOptions options = makeDefautOptions ();

        final SubscriptionPolledRefresh parameters = new SubscriptionPolledRefresh ();
        parameters.setOptions ( options );
        parameters.setWaitTime ( this.waitTime );
        parameters.getServerSubHandles ().add ( this.subscriptionHandle );

        logger.debug ( "Enter poll" );
        final SubscriptionPolledRefreshResponse result = soap.subscriptionPolledRefresh ( parameters );
        logger.debug ( "Poll returned" );

        synchronized ( this )
        {
            if ( this.subscriptionHandle == null )
            {
                // someone canceled our subscription from outside
                return;
            }

            if ( result.getInvalidServerSubHandles () != null && result.getInvalidServerSubHandles ().contains ( this.subscriptionHandle ) )
            {
                invalidate ();
            }
            else
            {
                updateValues ( makeValues ( result ) );
            }
        }
    }

    private void updateValues ( final Map<String, ItemValue> values )
    {
        fireDataUpdate ( values );
    }

    private Map<String, ItemValue> makeValues ( final SubscribeResponse result )
    {
        final Map<QName, String> errorMap = Helper.mapErrors ( result.getErrors () );
        final Map<String, ItemValue> values = new HashMap<> ();

        makeValuesSubscription ( values, errorMap, result.getRItemList ().getItems () );

        return values;
    }

    private Map<String, ItemValue> makeValues ( final SubscriptionPolledRefreshResponse result )
    {
        final Map<String, ItemValue> values = new HashMap<> ();

        final Map<QName, String> errorMap = Helper.mapErrors ( result.getErrors () );

        for ( final SubscribePolledRefreshReplyItemList list : result.getRItemList () )
        {
            logger.debug ( "{} items in reply", list.getItems ().size () );
            makeValues ( values, errorMap, list.getItems () );
        }

        return values;
    }

    private void makeValuesSubscription ( final Map<String, ItemValue> values, final Map<QName, String> errorMap, final List<SubscribeItemValue> list )
    {
        for ( final org.opcfoundation.webservices.xmlda._1.SubscribeItemValue value : list )
        {
            final ItemRequest req = this.handleMap.get ( value.getItemValue ().getClientItemHandle () );

            final ItemValue iv = Helper.convertValue ( value.getItemValue (), req.getItemName (), errorMap );
            values.put ( value.getItemValue ().getClientItemHandle (), iv );
        }
    }

    private void makeValues ( final Map<String, ItemValue> values, final Map<QName, String> errorMap, final List<org.opcfoundation.webservices.xmlda._1.ItemValue> list )
    {
        for ( final org.opcfoundation.webservices.xmlda._1.ItemValue value : list )
        {
            final ItemRequest req = this.handleMap.get ( value.getClientItemHandle () );

            final ItemValue iv = Helper.convertValue ( value, req.getItemName (), errorMap );
            values.put ( value.getClientItemHandle (), iv );
        }
    }

    private void invalidate ()
    {
        logger.info ( "Invalidating: {}", this.subscriptionHandle );
        fireStateUpdate ( SubscriptionState.INACTIVE );
        this.subscriptionHandle = null;
    }

    private RequestOptions makeDefautOptions ()
    {
        final RequestOptions options = new RequestOptions ();
        options.setReturnErrorText ( true );
        options.setReturnDiagnosticInfo ( true );
        options.setReturnItemTime ( true );
        return options;
    }

    protected synchronized void start ()
    {
        if ( this.thread != null )
        {
            return;
        }

        this.running = true;

        this.thread = new Thread () {
            @Override
            public void run ()
            {
                runPoller ();
            }
        };

        this.thread.setName ( this.name + "/" + this.threadCounter.incrementAndGet () );
        this.thread.start ();
    }

    protected void runPoller ()
    {
        logger.info ( "Starting poller" );
        try
        {
            fireStateUpdate ( SubscriptionState.INACTIVE );
            while ( this.running )
            {
                try
                {
                    if ( this.subscriptionHandle == null )
                    {
                        logger.info ( "Performing setup" );
                        setup ();
                        logger.info ( "Setup complete" );
                    }
                    if ( this.subscriptionHandle != null )
                    {
                        logger.debug ( "Performing poll" );
                        performPollOnce ();
                    }
                    synchronized ( this )
                    {
                        if ( this.items.isEmpty () )
                        {
                            logger.info ( "Waiting for items" );
                            fireStateUpdate ( SubscriptionState.WAITING );
                            wait ();
                        }
                    }
                }
                catch ( final Throwable e )
                {
                    logger.warn ( "Failed to poll", e );
                    invalidate ();
                }
            }
        }
        finally
        {
            logger.info ( "Exit poll loop" );
        }
    }

    private void fireStateUpdate ( final SubscriptionState state )
    {
        if ( this.listener == null )
        {
            return;
        }

        this.executor.execute ( new Runnable () {

            @Override
            public void run ()
            {
                Poller.this.listener.stateChange ( state );
            }
        } );
    }

    private void fireDataUpdate ( final Map<String, ItemValue> values )
    {
        if ( this.listener == null )
        {
            return;
        }

        this.executor.execute ( new Runnable () {

            @Override
            public void run ()
            {
                Poller.this.listener.dataChange ( values );
            }
        } );
    }

    public void dispose ()
    {
        logger.info ( "Disposing" );
        this.running = false;

        final Service soap = this.connection.unwrap ( Service.class );

        String subscriptionHandle;
        synchronized ( this )
        {
            subscriptionHandle = this.subscriptionHandle;
            this.subscriptionHandle = null;
        }

        if ( subscriptionHandle != null )
        {
            logger.info ( "Disposing at server..." );

            try
            {
                final SubscriptionCancel parameters = new SubscriptionCancel ();
                parameters.setServerSubHandle ( subscriptionHandle );
                soap.subscriptionCancel ( parameters );
            }
            catch ( final Exception e )
            {
                logger.warn ( "Failed to dispose at server", e );
            }

            logger.info ( "Disposing at server...done!" );
        }
    }

    public static List<ItemRequest> makeRequests ( final Collection<String> itemNames )
    {
        final ArrayList<ItemRequest> result = new ArrayList<> ( itemNames.size () );

        for ( final String itemName : itemNames )
        {
            result.add ( new ItemRequest ( itemName, itemName ) );
        }

        return result;
    }
}