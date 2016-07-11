/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.wave.federation.matrix;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;

import org.dom4j.Attribute;
import org.dom4j.Element;
import org.joda.time.DateTimeUtils;
import org.waveprotocol.wave.federation.FederationErrors;
import org.waveprotocol.wave.federation.FederationErrorProto.FederationError;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Represents Matrix room status for a specific remote domain. This class only
 * exposes one public method; {@link #getRoomForRemoteId}.
 *
 * @author khwaqee@gmail.com (Waqee Khalid)
 */
public class RemoteRoom {
  private static final Logger LOG = Logger.getLogger(RemoteDisco.class.getCanonicalName());

  enum Status {
    INIT, PENDING, COMPLETE
  }


  private final Random random = new SecureRandom();
  private MatrixPacketHandler handler; 
  private final String remoteId;
  private final AtomicReference<Status> status;
  private final Queue<SuccessFailCallback<String, String>> pending;

  // Result JID field that will be available on COMPLETE status.
  private String remoteRoom;

  // Error field that will be available on COMPLETE status.
  private FederationError error;


  // These two values are used for tracking success and failure counts.
  // Not yet exposed in the fedone waveserver.
  public static final LoadingCache<String, AtomicLong> statDiscoSuccess =
      CacheBuilder.newBuilder().build(new CacheLoader<String, AtomicLong>() {
            @Override
            public AtomicLong load(String remoteId) {
              return new AtomicLong();
            }
          });

  public static final LoadingCache<String, AtomicLong> statDiscoFailed =
      CacheBuilder.newBuilder().build(new CacheLoader<String, AtomicLong>() {
            @Override
            public AtomicLong load(String remoteId) {
              return new AtomicLong();
            }
          });

  public RemoteDisco(MatrixPacketHandler handler, String remoteId, long failExpirySecs,
                   long successExpirySecs) {
    this.handler = handler;
    status = new AtomicReference<Status>(Status.INIT);
    pending = new ConcurrentLinkedQueue<SuccessFailCallback<String, String>>();
    this.remoteId = remoteId;
    this.creationTimeMillis = DateTimeUtils.currentTimeMillis();
    this.failExpirySecs = failExpirySecs;
    this.successExpirySecs = successExpirySecs;
  }

  public void searchRemoteRoom(SuccessFailCallback<String, String> callback) {
    if (status.get().equals(Status.COMPLETE)) {
      complete(callback);
    } else if (status.compareAndSet(Status.INIT, Status.PENDING)) {
      pending.add(callback);
      startDisco();
    } else {
      pending.add(callback);

      // If we've become complete since the start of this method, complete
      // all possible callbacks.
      if (status.get().equals(Status.COMPLETE)) {
        SuccessFailCallback<String, String> item;
        while ((item = pending.poll()) != null) {
          complete(item);
        }
      }
    }
  }

  public boolean ttlExceeded() {
    if (status.get() == Status.COMPLETE) {
      if (remoteId == null) {
        // Failed disco case
        if (DateTimeUtils.currentTimeMillis() >
            (creationTimeMillis + (1000 * failExpirySecs))) {
          return true;
        }
      } else {
        // Successful disco case
        if (DateTimeUtils.currentTimeMillis() >
            (creationTimeMillis + (1000 * successExpirySecs))) {
          return true;
        }
      }
    }
    return false;
  }

  private void complete(SuccessFailCallback<String, String> callback) {
    Preconditions.checkState(status.get().equals(Status.COMPLETE));
    if (remoteId != null) {
      callback.onSuccess(remoteJid);
    } else {
      // TODO(thorogood): better toString, or change failure type to FederationError
      callback.onFailure(error.toString());
    }
  }

  private void startDisco() {

  }

}