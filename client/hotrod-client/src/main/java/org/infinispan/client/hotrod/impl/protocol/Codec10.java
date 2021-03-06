package org.infinispan.client.hotrod.impl.protocol;

import static org.infinispan.commons.util.Util.hexDump;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.VersionedMetadata;
import org.infinispan.client.hotrod.annotation.ClientListener;
import org.infinispan.client.hotrod.event.ClientEvent;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.client.hotrod.exceptions.InvalidResponseException;
import org.infinispan.client.hotrod.exceptions.RemoteNodeSuspectException;
import org.infinispan.client.hotrod.impl.transport.Transport;
import org.infinispan.client.hotrod.logging.Log;
import org.infinispan.client.hotrod.logging.LogFactory;
import org.infinispan.client.hotrod.marshall.MarshallerUtil;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.util.Either;
import org.infinispan.commons.util.Util;

/**
 * A Hot Rod encoder/decoder for version 1.0 of the protocol.
 *
 * @author Galder Zamarreño
 * @since 5.1
 */
public class Codec10 implements Codec {

   private static final Log log = LogFactory.getLog(Codec10.class, Log.class);
   protected final boolean trace = getLog().isTraceEnabled();

   static final AtomicLong MSG_ID = new AtomicLong();

   @Override
   public HeaderParams writeHeader(Transport transport, HeaderParams params) {
      return writeHeader(transport, params, HotRodConstants.VERSION_10);
   }

   @Override
   public void writeClientListenerParams(Transport transport, ClientListener clientListener,
         byte[][] filterFactoryParams, byte[][] converterFactoryParams) {
      // No-op
   }

   @Override
   public void writeExpirationParams(Transport transport, long lifespan, TimeUnit lifespanTimeUnit, long maxIdle, TimeUnit maxIdleTimeUnit) {
      if (!CodecUtils.isIntCompatible(lifespan)) {
         getLog().warn("Lifespan value greater than the max supported size (Integer.MAX_VALUE), this can cause precision loss");
      }
      if (!CodecUtils.isIntCompatible(maxIdle)) {
         getLog().warn("MaxIdle value greater than the max supported size (Integer.MAX_VALUE), this can cause precision loss");
      }
      int lifespanSeconds = CodecUtils.toSeconds(lifespan, lifespanTimeUnit);
      int maxIdleSeconds = CodecUtils.toSeconds(maxIdle, maxIdleTimeUnit);
      transport.writeVInt(lifespanSeconds);
      transport.writeVInt(maxIdleSeconds);
   }

   protected HeaderParams writeHeader(
            Transport transport, HeaderParams params, byte version) {
      transport.writeByte(HotRodConstants.REQUEST_MAGIC);
      transport.writeVLong(params.messageId(MSG_ID.incrementAndGet()).messageId);
      transport.writeByte(version);
      transport.writeByte(params.opCode);
      transport.writeArray(params.cacheName);

      int flagInt = params.flags & Flag.FORCE_RETURN_VALUE.getFlagInt(); // 1.0 / 1.1 servers only understand this flag
      transport.writeVInt(flagInt);
      transport.writeByte(params.clientIntel);
      transport.writeVInt(params.topologyId.get());
      //todo change once TX support is added
      transport.writeByte(params.txMarker);
      if (trace) getLog().tracef("Wrote header for message %d. Operation code: %#04x. Flags: %#x",
         params.messageId, params.opCode, flagInt);
      return params;
   }

   @Override
   public short readHeader(Transport transport, HeaderParams params) {
      short magic = transport.readByte();
      final Log localLog = getLog();
      if (magic != HotRodConstants.RESPONSE_MAGIC) {
         String message = "Invalid magic number. Expected %#x and received %#x";
         localLog.invalidMagicNumber(HotRodConstants.RESPONSE_MAGIC, magic);
         if (trace)
            localLog.tracef("Socket dump: %s", hexDump(transport.dumpStream()));
         throw new InvalidResponseException(String.format(message, HotRodConstants.RESPONSE_MAGIC, magic));
      }
      long receivedMessageId = transport.readVLong();
      // If received id is 0, it could be that a failure was noted before the
      // message id was detected, so don't consider it to a message id error
      if (receivedMessageId != params.messageId && receivedMessageId != 0) {
         String message = "Invalid message id. Expected %d and received %d";
         localLog.invalidMessageId(params.messageId, receivedMessageId);
         if (trace)
            localLog.tracef("Socket dump: %s", hexDump(transport.dumpStream()));
         throw new InvalidResponseException(String.format(message, params.messageId, receivedMessageId));
      }
      if (trace)
         localLog.tracef("Received response for message id: %d", receivedMessageId);

      short receivedOpCode = transport.readByte();
      // Read both the status and new topology (if present),
      // before deciding how to react to error situations.
      short status = transport.readByte();
      readNewTopologyIfPresent(transport, params);

      // Now that all headers values have been read, check the error responses.
      // This avoids situatiations where an exceptional return ends up with
      // the socket containing data from previous request responses.
      if (receivedOpCode != params.opRespCode) {
         if (receivedOpCode == HotRodConstants.ERROR_RESPONSE) {
            checkForErrorsInResponseStatus(transport, params, status);
         }
         throw new InvalidResponseException(String.format(
               "Invalid response operation. Expected %#x and received %#x",
               params.opRespCode, receivedOpCode));
      }
      if (trace) localLog.tracef("Received operation code is: %#04x", receivedOpCode);

      return status;
   }

   @Override
   public ClientEvent readEvent(Transport transport, byte[] expectedListenerId, Marshaller marshaller, List<String> whitelist) {
      return null;  // No events sent in Hot Rod 1.x protocol
   }

   @Override
   public Either<Short, ClientEvent> readHeaderOrEvent(Transport transport, HeaderParams params, byte[] expectedListenerId, Marshaller marshaller, List<String> whitelist) {
      return null;  // No events sent in Hot Rod 1.x protocol
   }

   @Override
   public Object returnPossiblePrevValue(Transport transport, short status, int flags, List<String> whitelist) {
      Marshaller marshaller = transport.getTransportFactory().getMarshaller();
      if (hasForceReturn(flags)) {
         byte[] bytes = transport.readArray();
         if (trace) getLog().tracef("Previous value bytes is: %s", Util.printArray(bytes, false));
         //0-length response means null
         return bytes.length == 0 ? null : MarshallerUtil.bytes2obj(marshaller, bytes, status, whitelist);
      } else {
         return null;
      }
   }

   private boolean hasForceReturn(int flags) {
      return (flags & Flag.FORCE_RETURN_VALUE.getFlagInt()) != 0;
   }

   @Override
   public Log getLog() {
      return log;
   }

   @Override
   public <T> T readUnmarshallByteArray(Transport transport, short status, List<String> whitelist) {
      return CodecUtils.readUnmarshallByteArray(transport, status, whitelist);
   }

   @Override
   public <T extends InputStream & VersionedMetadata> T readAsStream(Transport transport, VersionedMetadata versionedMetadata, Runnable afterClose) {
      throw new UnsupportedOperationException();
   }

   @Override
   public OutputStream writeAsStream(Transport transport, Runnable afterClose) {
      throw new UnsupportedOperationException();
   }

   public void writeClientListenerInterests(Transport transport, Set<Class<? extends Annotation>> classes) {
      // No-op
   }

   protected void checkForErrorsInResponseStatus(Transport transport, HeaderParams params, short status) {
      final Log localLog = getLog();
      if (trace) localLog.tracef("Received operation status: %#x", status);

      try {
         switch (status) {
            case HotRodConstants.INVALID_MAGIC_OR_MESSAGE_ID_STATUS:
            case HotRodConstants.REQUEST_PARSING_ERROR_STATUS:
            case HotRodConstants.UNKNOWN_COMMAND_STATUS:
            case HotRodConstants.SERVER_ERROR_STATUS:
            case HotRodConstants.COMMAND_TIMEOUT_STATUS:
            case HotRodConstants.UNKNOWN_VERSION_STATUS: {
               // If error, the body of the message just contains a message
               String msgFromServer = transport.readString();
               if (status == HotRodConstants.COMMAND_TIMEOUT_STATUS && trace) {
                  localLog.tracef("Server-side timeout performing operation: %s", msgFromServer);
               } if (msgFromServer.contains("SuspectException")
                     || msgFromServer.contains("SuspectedException")) {
                  // Handle both Infinispan's and JGroups' suspicions
                  if (trace)
                     localLog.tracef("A remote node was suspected while executing messageId=%d. " +
                        "Check if retry possible. Message from server: %s", params.messageId, msgFromServer);
                  // TODO: This will be better handled with its own status id in version 2 of protocol
                  throw new RemoteNodeSuspectException(msgFromServer, params.messageId, status);
               } else {
                  localLog.errorFromServer(msgFromServer);
               }
               throw new HotRodClientException(msgFromServer, params.messageId, status);
            }
            default: {
               throw new IllegalStateException(String.format("Unknown status: %#04x", status));
            }
         }
      } finally {
         // Errors related to protocol parsing are odd, and they can sometimes
         // be the consequence of previous errors, so whenever these errors
         // occur, invalidate the underlying transport instance so that a
         // brand new connection is established next time around.
         switch (status) {
            case HotRodConstants.INVALID_MAGIC_OR_MESSAGE_ID_STATUS:
            case HotRodConstants.REQUEST_PARSING_ERROR_STATUS:
            case HotRodConstants.UNKNOWN_COMMAND_STATUS:
            case HotRodConstants.UNKNOWN_VERSION_STATUS: {
               transport.invalidate();
            }
         }
      }
   }

   protected void readNewTopologyIfPresent(Transport transport, HeaderParams params) {
      short topologyChangeByte = transport.readByte();
      if (topologyChangeByte == 1)
         readNewTopologyAndHash(transport, params.topologyId, params.cacheName);
   }

   protected void readNewTopologyAndHash(Transport transport, AtomicInteger topologyId, byte[] cacheName) {
      final Log localLog = getLog();
      int newTopologyId = transport.readVInt();
      topologyId.set(newTopologyId);
      int numKeyOwners = transport.readUnsignedShort();
      short hashFunctionVersion = transport.readByte();
      int hashSpace = transport.readVInt();
      int clusterSize = transport.readVInt();

      Map<SocketAddress, Set<Integer>> servers2Hash = computeNewHashes(
            transport, localLog, newTopologyId, numKeyOwners,
            hashFunctionVersion, hashSpace, clusterSize);

      Set<SocketAddress> socketAddresses = servers2Hash.keySet();
      int topologyAge = transport.getTransportFactory().getTopologyAge();
      if (localLog.isInfoEnabled()) {
         localLog.newTopology(transport.getRemoteSocketAddress(), newTopologyId, topologyAge,
               socketAddresses.size(), socketAddresses);
      }
      transport.getTransportFactory().updateServers(socketAddresses, cacheName, false);
      if (hashFunctionVersion == 0) {
         localLog.trace("Not using a consistent hash function (version 0).");
      } else if (hashFunctionVersion == 1) {
         localLog.trace("Ignoring obsoleted consistent hash function (version 1)");
      } else {
         transport.getTransportFactory().updateHashFunction(
               servers2Hash, numKeyOwners, hashFunctionVersion, hashSpace, cacheName, topologyId);
      }
   }

   protected Map<SocketAddress, Set<Integer>> computeNewHashes(Transport transport,
         Log localLog, int newTopologyId, int numKeyOwners,
         short hashFunctionVersion, int hashSpace, int clusterSize) {
      if (trace) {
         localLog.tracef("Topology change request: newTopologyId=%d, numKeyOwners=%d, " +
                       "hashFunctionVersion=%d, hashSpaceSize=%d, clusterSize=%d",
                 newTopologyId, numKeyOwners, hashFunctionVersion, hashSpace, clusterSize);
      }

      Map<SocketAddress, Set<Integer>> servers2Hash = new LinkedHashMap<SocketAddress, Set<Integer>>();

      for (int i = 0; i < clusterSize; i++) {
         String host = transport.readString();
         int port = transport.readUnsignedShort();
         int hashCode = transport.read4ByteInt();
         if (trace) localLog.tracef("Server read: %s:%d - hash code is %d", host, port, hashCode);
         SocketAddress address = InetSocketAddress.createUnresolved(host, port);
         Set<Integer> hashes = servers2Hash.get(address);
         if (hashes == null) {
            hashes = new HashSet<Integer>();
            servers2Hash.put(address, hashes);
         }
         hashes.add(hashCode);
         if (trace) localLog.tracef("Hash code is: %d", hashCode);
      }
      return servers2Hash;
   }

}
