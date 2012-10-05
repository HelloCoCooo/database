/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

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
package com.bigdata.ha;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;

import com.bigdata.ha.msg.HA2PhaseAbortMessage;
import com.bigdata.ha.msg.HA2PhaseCommitMessage;
import com.bigdata.ha.msg.HA2PhasePrepareMessage;
import com.bigdata.ha.msg.IHA2PhaseAbortMessage;
import com.bigdata.ha.msg.IHA2PhaseCommitMessage;
import com.bigdata.ha.msg.IHA2PhasePrepareMessage;
import com.bigdata.journal.IRootBlockView;
import com.bigdata.quorum.Quorum;
import com.bigdata.quorum.QuorumMember;
import com.bigdata.quorum.QuorumStateChangeListener;
import com.bigdata.quorum.QuorumStateChangeListenerBase;
import com.bigdata.util.concurrent.ExecutionExceptions;

/**
 * {@link QuorumCommit} implementation.
 */
public class QuorumCommitImpl<S extends HACommitGlue> extends
        QuorumStateChangeListenerBase implements QuorumCommit<S>,
        QuorumStateChangeListener {

    static protected transient final Logger log = Logger
            .getLogger(QuorumCommitImpl.class);

    protected final QuorumMember<S> member;
    
    /**
     * The downstream service in the write pipeline.
     */
    protected volatile UUID downStreamId = null;

    public QuorumCommitImpl(final QuorumMember<S> member) {
        
        this.member = member;
        
    }

    protected Quorum<?, ?> getQuorum() {
        
        return member.getQuorum();
        
    }
    
    protected HACommitGlue getService(final UUID serviceId) {

        return member.getService(serviceId);
        
    }

    /**
     * Cancel the requests on the remote services (RMI). This is a best effort
     * implementation. Any RMI related errors are trapped and ignored in order
     * to be robust to failures in RMI when we try to cancel the futures.
     */
    protected <F extends Future<T>, T> void cancelRemoteFutures(
            final List<F> remoteFutures) {

        if (log.isInfoEnabled())
            log.info("");

        for (F rf : remoteFutures) {

            try {

                rf.cancel(true/* mayInterruptIfRunning */);

            } catch (Throwable t) {

                // ignored (to be robust).

            }

        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation runs the operation on the leader in the caller's
     * thread to avoid deadlock. The other services run the operation
     * asynchronously on their side while the leader awaits their future's using
     * get().
     */
    public int prepare2Phase(//final boolean isRootBlock0,
            final IRootBlockView rootBlock, final long timeout,
            final TimeUnit unit) throws InterruptedException, TimeoutException,
            IOException {

        if (rootBlock == null)
            throw new IllegalArgumentException();

        if (unit == null)
            throw new IllegalArgumentException();
        
        final boolean isRootBlock0 = rootBlock.isRootBlock0();

        if (log.isInfoEnabled())
            log.info("isRootBlock0=" + isRootBlock0 + "rootBlock=" + rootBlock
                    + ", timeout=" + timeout + ", unit=" + unit);

        /*
         * The token of the quorum for which the leader issued this prepare
         * message.
         */
        final long token = rootBlock.getQuorumToken();

        /*
         * To minimize latency, we first submit the futures for the other
         * services and then do f.run() on the leader. This will allow the other
         * services to prepare concurrently with the leader's IO.
         */

        final long begin = System.nanoTime();
        final long nanos = unit.toNanos(timeout);
        long remaining = nanos;

        int nyes = 0;

        // // Copy the root block into a byte[].
        // final byte[] data;
        // {
        // final ByteBuffer rb = rootBlock.asReadOnlyBuffer();
        // data = new byte[rb.limit()];
        // rb.get(data);
        // }

        final List<Future<Boolean>> remoteFutures = new LinkedList<Future<Boolean>>();

        /*
         * For services (other than the leader) in the quorum, submit the
         * RunnableFutures to an Executor.
         */
        final UUID[] joinedServiceIds = getQuorum().getJoined();
        
        // Verify the quorum is valid.
        member.assertLeader(token);
        
//        final byte[] tmp = BytesUtil.toArray(rootBlock.asReadOnlyBuffer());
        final IHA2PhasePrepareMessage msg = new HA2PhasePrepareMessage(
                rootBlock, timeout, unit);
        
        for (int i = 1; i < joinedServiceIds.length; i++) {
            
            final UUID serviceId = joinedServiceIds[i];

            /*
             * Runnable which will execute this message on the remote service.
             */
            final Future<Boolean> rf = getService(serviceId).prepare2Phase(msg);

            // add to list of futures we will check.
            remoteFutures.add(rf);

//            /*
//             * Submit the runnable for execution by the leader's
//             * ExecutorService. When the runnable runs it will execute the
//             * message on the remote service using RMI.
//             */
//            member.getExecutor().execute(rf);

        }

        {
            /*
             * Run the operation on the leader using local method call (non-RMI)
             * in the caller's thread to avoid deadlock.
             * 
             * Note: Because we are running this in the caller's thread on the
             * leader the timeout will be ignored for the leader.
             */
            final S leader = member.getService();
            final Future<Boolean> f = leader.prepare2Phase(msg);
            remoteFutures.add(f);
//            /*
//             * Note: This runs synchronously in the caller's thread (it ignores
//             * timeout).
//             */
//            f.run();
//            try {
//                remaining = nanos - (begin - System.nanoTime());
//                nyes += f.get(remaining, TimeUnit.NANOSECONDS) ? 1 : 0;
//            } catch (ExecutionException e) {
//                // Cancel remote futures.
//                cancelRemoteFutures(remoteFutures);
//                // Error on the leader.
//                throw new RuntimeException(e);
//            } finally {
//                f.cancel(true/* mayInterruptIfRunning */);
//            }
        }

        /*
         * Check the futures for the other services in the quorum.
         */
        for (Future<Boolean> rf : remoteFutures) {
            boolean done = false;
            try {
                remaining = nanos - (begin - System.nanoTime());
                nyes += rf.get(remaining, TimeUnit.NANOSECONDS) ? 1 : 0;
                done = true;
            } catch (ExecutionException ex) {
                log.error(ex, ex);
            } finally {
                if (!done) {
                    // Cancel the request on the remote service (RMI).
                    try {
                        rf.cancel(true/* mayInterruptIfRunning */);
                    } catch (Throwable t) {
                        // ignored.
                    }
                }
            }
        }

        final int k = getQuorum().replicationFactor();

        if (!getQuorum().isQuorum(nyes)) {

            log.error("prepare rejected: nyes=" + nyes + " out of " + k);

        }

        return nyes;

    }

    public void commit2Phase(final long token, final long commitTime)
            throws IOException, InterruptedException {

        if (log.isInfoEnabled())
            log.info("token=" + token + ", commitTime=" + commitTime);

        /*
         * To minimize latency, we first submit the futures for the other
         * services and then do f.run() on the leader. This will allow the other
         * services to commit concurrently with the leader's IO.
         */

        final List<Future<Void>> remoteFutures = new LinkedList<Future<Void>>();

        /*
         * For services (other than the leader) in the quorum, submit the
         * RunnableFutures to an Executor.
         */
        final UUID[] joinedServiceIds = getQuorum().getJoined();
        
        member.assertLeader(token);

        final IHA2PhaseCommitMessage msg = new HA2PhaseCommitMessage(commitTime);
        
        for (int i = 1; i < joinedServiceIds.length; i++) {
            
            final UUID serviceId = joinedServiceIds[i];

            /*
             * Runnable which will execute this message on the remote service.
             */
            final Future<Void> rf = getService(serviceId).commit2Phase(msg);

            // add to list of futures we will check.
            remoteFutures.add(rf);

//            /*
//             * Submit the runnable for execution by the leader's
//             * ExecutorService. When the runnable runs it will execute the
//             * message on the remote service using RMI.
//             */
//            member.getExecutor().execute(rf);

        }

        {
            /*
             * Run the operation on the leader using local method call in the
             * caller's thread to avoid deadlock.
             */
            final S leader = member.getService();
            final Future<Void> f = leader.commit2Phase(msg);
            remoteFutures.add(f);
//            // Note: This runs synchronously (ignores timeout).
//            f.run();
//            try {
//                f.get();
//            } catch (ExecutionException e) {
//                // Cancel remote futures.
//                cancelRemoteFutures(remoteFutures);
//                // Error on the leader.
//                throw new RuntimeException(e);
//            } finally {
//                f.cancel(true/* mayInterruptIfRunning */);
//            }
        }

        /*
         * Check the futures for the other services in the quorum.
         */
        final List<Throwable> causes = new LinkedList<Throwable>();
        for (Future<Void> rf : remoteFutures) {
            boolean done = false;
            try {
                rf.get();
                done = true;
            } catch (InterruptedException ex) {
                log.error(ex, ex);
                causes.add(ex);
            } catch (ExecutionException ex) {
                log.error(ex, ex);
                causes.add(ex);
            } finally {
                if (!done) {
                    // Cancel the request on the remote service (RMI).
                    try {
                        rf.cancel(true/* mayInterruptIfRunning */);
                    } catch (Throwable t) {
                        // ignored.
                    }
                }
            }
        }

        /*
         * If there were any errors, then throw an exception listing them.
         */
        if (!causes.isEmpty()) {
            // Cancel remote futures.
            cancelRemoteFutures(remoteFutures);
            // Throw exception back to the leader.
            throw new RuntimeException("remote errors: nfailures="
                    + causes.size(), new ExecutionExceptions(causes));
        }

    }

    public void abort2Phase(final long token) throws IOException,
            InterruptedException {

        if (log.isInfoEnabled())
            log.info("token=" + token);

        /*
         * To minimize latency, we first submit the futures for the other
         * services and then do f.run() on the leader. This will allow the other
         * services to commit concurrently with the leader's IO.
         */

        final List<Future<Void>> remoteFutures = new LinkedList<Future<Void>>();

        /*
         * For services (other than the leader) in the quorum, submit the
         * RunnableFutures to an Executor.
         */
        final UUID[] joinedServiceIds = getQuorum().getJoined();

        member.assertLeader(token);
        
        final IHA2PhaseAbortMessage msg = new HA2PhaseAbortMessage(token);

        for (int i = 1; i < joinedServiceIds.length; i++) {

            final UUID serviceId = joinedServiceIds[i];

            /*
             * Runnable which will execute this message on the remote service.
             */
            final Future<Void> rf = getService(serviceId).abort2Phase(msg);

            // add to list of futures we will check.
            remoteFutures.add(rf);

//            /*
//             * Submit the runnable for execution by the leader's
//             * ExecutorService. When the runnable runs it will execute the
//             * message on the remote service using RMI.
//             */
//            member.getExecutor().execute(rf);

        }

        {
            /*
             * Run the operation on the leader using a local method call
             * (non-RMI) in the caller's thread to avoid deadlock.
             */
            member.assertLeader(token);
            final S leader = member.getService();
            final Future<Void> f = leader.abort2Phase(msg);
            remoteFutures.add(f);
//            // Note: This runs synchronously (ignores timeout).
//            f.run();
//            try {
//                f.get();
//            } catch (ExecutionException e) {
//                // Cancel remote futures.
//                cancelRemoteFutures(remoteFutures);
//                // Error on the leader.
//                throw new RuntimeException(e);
//            } finally {
//                f.cancel(true/* mayInterruptIfRunning */);
//            }
        }

        /*
         * Check the futures for the other services in the quorum.
         */
        final List<Throwable> causes = new LinkedList<Throwable>();
        for (Future<Void> rf : remoteFutures) {
            boolean done = false;
            try {
                rf.get();
                done = true;
            } catch (InterruptedException ex) {
                log.error(ex, ex);
                causes.add(ex);
            } catch (ExecutionException ex) {
                log.error(ex, ex);
                causes.add(ex);
            } finally {
                if (!done) {
                    // Cancel the request on the remote service (RMI).
                    try {
                        rf.cancel(true/* mayInterruptIfRunning */);
                    } catch (Throwable t) {
                        // ignored.
                    }
                }
            }
        }

        /*
         * If there were any errors, then throw an exception listing them.
         */
        if (!causes.isEmpty()) {
            // Cancel remote futures.
            cancelRemoteFutures(remoteFutures);
            // Throw exception back to the leader.
            throw new RuntimeException("remote errors: nfailures="
                    + causes.size(), new ExecutionExceptions(causes));
        }

    }

}
