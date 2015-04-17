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

public class ItemRequest
{
    private final String clientHandle;

    private final String itemName;

    public ItemRequest ( final String clientHandle, final String itemName )
    {
        this.clientHandle = clientHandle;
        this.itemName = itemName;
    }

    public String getClientHandle ()
    {
        return this.clientHandle;
    }

    public String getItemName ()
    {
        return this.itemName;
    }

}