/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.kernel.api.helpers;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.neo4j.internal.kernel.api.SchemaRead;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.register.Register;
import org.neo4j.register.Registers;
import org.neo4j.time.Stopwatch;

public class Indexes
{
    /**
     * For each index, await a resampling event unless it has zero pending updates.
     *
     * @param schemaRead backing schema read
     * @param timeoutSeconds timeout in seconds. If this limit is passed, a TimeoutException is thrown.
     * @throws TimeoutException if all indexes are not resampled within the timeout.
     */
    public static void awaitResampling( SchemaRead schemaRead, long timeoutSeconds ) throws TimeoutException
    {
        final Iterator<IndexDescriptor> indexes = schemaRead.indexesGetAll();
        final Register.DoubleLongRegister register = Registers.newDoubleLongRegister();
        final Stopwatch startTime = Stopwatch.start();

        while ( indexes.hasNext() )
        {
            IndexDescriptor index = indexes.next();
            try
            {
                long readUpdates = readUpdates( index, schemaRead, register );
                long updateCount = readUpdates;
                boolean hasTimedOut = false;

                while ( updateCount > 0 && updateCount <= readUpdates && !hasTimedOut )
                {
                    Thread.sleep( 10 );
                    hasTimedOut = startTime.hasTimedOut( timeoutSeconds, TimeUnit.SECONDS );
                    updateCount = Math.max( updateCount, readUpdates );
                    readUpdates = readUpdates( index, schemaRead, register );
                }

                if ( hasTimedOut )
                {
                    throw new TimeoutException( String.format( "Indexes were not resampled within %s %s", timeoutSeconds, TimeUnit.SECONDS ) );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException( e );
            }
            catch ( IndexNotFoundKernelException e )
            {
                throw new ConcurrentModificationException( "Index was dropped while awaiting resampling", e );
            }
        }
    }

    private static long readUpdates( IndexDescriptor index, SchemaRead schemaRead, Register.DoubleLongRegister register ) throws IndexNotFoundKernelException
    {
        schemaRead.indexUpdatesAndSize( index, register );
        return register.readFirst();
    }
}