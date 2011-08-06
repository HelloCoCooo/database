/**

Copyright (C) SYSTAP, LLC 2006-2011.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Aug 6, 2011
 */

package com.bigdata.bop.solutions;

import java.util.UUID;

import com.bigdata.bop.IQueryContext;
import com.bigdata.io.DirectBufferPool;
import com.bigdata.rwstore.sector.IMemoryManager;
import com.bigdata.rwstore.sector.MemoryManager;

/**
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class MockQueryContext implements IQueryContext {
    
    private final UUID queryId;

    private volatile IMemoryManager memoryManager;

    public MockQueryContext(final UUID queryId) {

        this.queryId = queryId;

        this.memoryManager = new MemoryManager(DirectBufferPool.INSTANCE);

    }

    public UUID getQueryId() {
        return queryId;
    }

    public IMemoryManager getMemoryManager() {
        return memoryManager;
    }

    synchronized public void close() {

        final IMemoryManager memoryManager = this.memoryManager;
        
        if (memoryManager != null)
            memoryManager.clear();
        
        this.memoryManager = null;

    }
    
    protected void finalize() throws Throwable {
        
        close();
        
    }

}
