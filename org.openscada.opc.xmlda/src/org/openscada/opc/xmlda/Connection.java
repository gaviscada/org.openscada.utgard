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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Service;

import org.eclipse.scada.utils.concurrent.ExportedExecutorService;
import org.eclipse.scada.utils.concurrent.FutureTask;
import org.eclipse.scada.utils.concurrent.NotifyFuture;
import org.eclipse.scada.utils.concurrent.ScheduledExportedExecutorService;
import org.openscada.opc.xmlda.browse.Browser;
import org.openscada.opc.xmlda.browse.BrowserListener;
import org.openscada.opc.xmlda.requests.BrowseEntry;
import org.openscada.opc.xmlda.requests.ReadRequest;

public class Connection implements AutoCloseable
{
    private org.opcfoundation.webservices.xmlda._1.Service soap;

    private final ScheduledExecutorService executor;

    private final ExecutorService eventExecutor;

    private final String name;

    private final int requestTimeout;

    private final QName serviceName;

    private final String localPortName;

    private final int connectTimeout;

    private final URL wsdlUrl;

    private final URL serverUrl;

    protected class TaskRunner<T> extends FutureTask<T>
    {
        public TaskRunner ( final Task<T> serviceCall )
        {
            super ( new Callable<T> () {

                @Override
                public T call () throws Exception
                {
                    return serviceCall.process ( Connection.this );
                }
            } );
        }
    }

    /**
     * Create a connection
     * <p>
     * This constructor takes a default connection timeout of 5 seconds, and
     * request timeout of 10 seconds
     * </p>
     *
     * @param url
     *            the endpoint and WSDL URL
     * @param serviceName
     *            the name of the service
     * @throws MalformedURLException
     *             thrown in case the URL has an invalid syntax
     */
    public Connection ( final String url, final String serviceName ) throws MalformedURLException
    {
        this ( new URL ( url + "?wsdl" ), new URL ( url ), new QName ( "http://opcfoundation.org/webservices/XMLDA/1.0/", serviceName ), serviceName + "Soap", 5_000, 10_000 );
    }

    /**
     * Create a connection with more control over the connection parameters
     *
     * @param wsdlUrl
     *            the URL of the WSDL file. This may be <code>null</code> in
     *            which case the serverUrl is used. This URL may point to a file
     *            system local resource.
     * @param serverUrl
     *            The URL to the server endpoint. This URL will override any
     *            endpoint in the WSDL file.
     * @param serviceName
     *            The service name
     * @param localPortName
     *            The local port name
     * @param connectTimeout
     *            The connection timeout (in milliseconds)
     * @param requestTimeout
     *            The request timeout (in milliseconds)
     */
    public Connection ( final URL wsdlUrl, final URL serverUrl, final QName serviceName, final String localPortName, final int connectTimeout, final int requestTimeout )
    {
        if ( serverUrl == null )
        {
            throw new NullPointerException ( "'serverUrl' must not be null" );
        }

        this.wsdlUrl = wsdlUrl;
        this.serverUrl = serverUrl;
        this.name = serverUrl + "/" + localPortName;

        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;

        this.serviceName = serviceName;
        this.localPortName = localPortName;

        this.executor = new ScheduledExportedExecutorService ( this.name + "/Requests" );
        this.eventExecutor = new ExportedExecutorService ( this.name + "/Events", 1, 1, 1, TimeUnit.SECONDS );
    }

    protected org.opcfoundation.webservices.xmlda._1.Service createPort ()
    {
        if ( this.soap != null )
        {
            return this.soap;
        }

        final QName portName = new QName ( this.serviceName.getNamespaceURI (), this.localPortName );

        final Service service = Service.create ( this.wsdlUrl == null ? this.serverUrl : this.wsdlUrl, this.serviceName );
        this.soap = service.getPort ( portName, org.opcfoundation.webservices.xmlda._1.Service.class );

        final BindingProvider bindingProvider = (BindingProvider)this.soap;

        final Map<String, Object> context = bindingProvider.getRequestContext ();

        context.put ( "javax.xml.ws.client.connectionTimeout", this.connectTimeout );
        context.put ( "javax.xml.ws.client.receiveTimeout", this.requestTimeout );
        context.put ( "com.sun.xml.internal.ws.connect.timeout", this.connectTimeout );
        context.put ( "com.sun.xml.internal.ws.request.timeout", this.requestTimeout );
        context.put ( BindingProvider.ENDPOINT_ADDRESS_PROPERTY, this.serverUrl.toString () );
        List<String> userPassword = this.serverUrl.getUserInfo () == null ? Collections.<String>emptyList () : Arrays.asList( this.serverUrl.getUserInfo ().split ( ":" ) );
        if ( userPassword.size() == 2 )
        {
        	context.put ( BindingProvider.PASSWORD_PROPERTY, userPassword.get ( 1 ) );
        }
        if ( userPassword.size() >= 1 )
        {
        	context.put ( BindingProvider.USERNAME_PROPERTY, userPassword.get ( 0 ) );
        }

        return this.soap;
    }

    public Poller createSubscriptionPoller ( final SubscriptionListener listener )
    {
        return new SubscriptionPoller ( this, this.eventExecutor, listener, (int) ( this.requestTimeout * 0.8 + 1000.0 ), 100 );
    }

    /**
     * Create a new subscription based poller
     */
    public Poller createSubscriptionPoller ( final SubscriptionListener listener, final int waitTime, final Integer samplingRate )
    {
        return new SubscriptionPoller ( this, this.eventExecutor, listener, waitTime, samplingRate );
    }

    /**
     * Create a new poller using {@link ReadRequest}s instead of subscriptions
     * <p>
     * The {@link ReadRequest} should only be used for one-shot read operations.
     * Continuous data acquisition should be realized using subscriptions (see
     * {@link #createSubscriptionPoller(SubscriptionListener, int, Integer)}).
     * However some server implementations are quite buggy so a emulating a
     * subscription by falling back to {@link ReadRequest}s might be a last
     * resort.
     * </p>
     *
     * @param period
     *            the poll period in milliseconds
     * @param maxAge
     *            the maximum data age, used by the server to evaluate if the
     *            data should be fetched from the cache or the device
     */
    public Poller createReadPoller ( final SubscriptionListener listener, final long period, final Integer maxAge )
    {
        return new ReadRequestPoller ( this, listener, this.eventExecutor, period, maxAge );
    }

    public Browser createBrowser ( final String itemName, final String itemPath, final BrowserListener listener, final long scanDelay, final int batchSize, final boolean fullProperties )
    {
        return new Browser ( itemName, itemPath, this, this.executor, this.eventExecutor, listener, scanDelay, batchSize, fullProperties );
    }

    public Browser createBrowser ( final BrowseEntry entry, final BrowserListener listener, final long scanDelay, final int batchSize, final boolean fullProperties )
    {
        return createBrowser ( entry.getItemName (), entry.getItemPath (), listener, scanDelay, batchSize, fullProperties );
    }

    public Browser createRootBrowser ( final BrowserListener listener, final long scanDelay, final int batchSize, final boolean fullProperties )
    {
        return createBrowser ( null, null, listener, scanDelay, batchSize, fullProperties );
    }

    public <S> S unwrap ( final Class<S> clazz )
    {
        if ( clazz.equals ( org.opcfoundation.webservices.xmlda._1.Service.class ) )
        {
            return clazz.cast ( createPort () );
        }
        return null;
    }

    public <T> NotifyFuture<T> scheduleTask ( final Task<T> serviceCall )
    {
        final TaskRunner<T> runner = new TaskRunner<> ( serviceCall );
        this.executor.execute ( runner );
        return runner;
    }

    @Override
    public void close () throws Exception
    {
        try
        {
            // shutdown requests now

            final List<Runnable> remaining;

            // FIXME: shut down pollers

            synchronized ( this )
            {
                remaining = this.executor.shutdownNow ();
            }

            for ( final Runnable runner : remaining )
            {
                if ( runner instanceof TaskRunner<?> )
                {
                    ( (TaskRunner<?>)runner ).cancel ( false );
                }
            }
        }
        finally
        {
            // shutdown events
            this.eventExecutor.shutdown ();
        }
    }

    @Override
    public String toString ()
    {
        return this.name;
    }
}
