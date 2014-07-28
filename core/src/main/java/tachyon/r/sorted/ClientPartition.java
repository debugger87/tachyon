package tachyon.r.sorted;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;

import com.google.common.base.Strings;

import tachyon.Constants;
import tachyon.client.OutStream;
import tachyon.client.TachyonFS;
import tachyon.client.TachyonFile;
import tachyon.client.WriteType;
import tachyon.thrift.SortedStorePartitionInfo;
import tachyon.util.CommonUtils;

/**
 * Each partition contains key/value pairs, and indices.
 * 
 * This class should be abstract, and have different kinds of implementations. But for the first
 * step, it has only one implementation.
 */
public class ClientPartition {
  public static ClientPartition createPartitionSortedStorePartition(TachyonFS tfs, int storeId,
      String storePath, int index) throws IOException {
    return new ClientPartition(tfs, storeId, storePath, index, true);
  }

  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);
  private final TachyonFS TFS;
  private final int STORE_ID;
  private final String STORE_PATH;
  private final int INDEX;

  // private final Serializer KEY_SER;
  // private final Serializer VALUE_SER;

  private final boolean CREATE;

  private String mPartitionPath;
  private String mDataFilePath;
  private TachyonFile mDataFile;
  private int mDataFileId;

  private OutStream mDataFileOutStream;
  private String mIndexFilePath;
  private TachyonFile mIndexFile;
  private int mIndexFileId;

  private OutStream mIndexFileOutStream;
  private ByteBuffer mStartKey;
  private ByteBuffer mEndKey;

  private int mDataFileLocation;

  ClientPartition(TachyonFS tfs, int storeId, String storePath, int index, boolean create)
      throws IOException {
    TFS = tfs;
    STORE_ID = storeId;
    STORE_PATH = storePath;
    INDEX = index;
    CREATE = create;

    mPartitionPath =
        CommonUtils.concat(STORE_PATH,
            "partition-" + Strings.padStart(Integer.toString(index), 5, '0'));
    mDataFilePath = mPartitionPath + "-data";
    mIndexFilePath = mPartitionPath + "-index";
    LOG.info("Creating KV partition: " + toString());

    if (create) {
      mDataFileId = TFS.createFile(mDataFilePath, Constants.GB);
      mDataFile = TFS.getFile(mDataFileId);
      mDataFileOutStream = mDataFile.getOutStream(WriteType.CACHE_THROUGH);

      mIndexFileId = TFS.createFile(mIndexFilePath, Constants.GB);
      mIndexFile = TFS.getFile(mIndexFileId);
      mIndexFileOutStream = mIndexFile.getOutStream(WriteType.CACHE_THROUGH);

      if (mDataFileId == -1 || mIndexFileId == -1) {
        throw new IOException("Failed to create data file or index file, or both.");
      }
    } else {
      mDataFile = TFS.getFile(mDataFilePath);
      mIndexFile = TFS.getFile(mIndexFilePath);
    }

    mDataFileLocation = 0;
    mStartKey = null;
    mEndKey = null;
  }

  public void close() throws IOException {
    if (CREATE) {
      mDataFileOutStream.close();
      mIndexFileOutStream.close();
      SortedStorePartitionInfo info = new SortedStorePartitionInfo();
      info.setStoreId(STORE_ID);
      info.setPartitionIndex(INDEX);
      info.setDataFileId(mDataFileId);
      info.setIndexFileId(mIndexFileId);
      if (mStartKey == null) {
        mStartKey = ByteBuffer.allocate(0);
        mEndKey = ByteBuffer.allocate(0);
      }
      info.setStartKey(mStartKey.array());
      info.setEndKey(mEndKey.array());
      try {
        TFS.r_addPartition(info);
      } catch (Exception e) {
        LOG.error(e);
      }
      LOG.info("closing: " + info);
    }
  }

  public void put(byte[] key, byte[] value) throws IOException {
    if (!CREATE) {
      throw new IOException("Can not put key value pair in non-create mode");
    }

    if (mStartKey == null) {
      mStartKey = ByteBuffer.allocate(key.length);
      mStartKey.put(key);
      mStartKey.flip();
    }
    if (mEndKey != null && Utils.compare(mEndKey.array(), key) > 0) {
      throw new IOException("Keys must be in sorted order!");
    }
    mEndKey = ByteBuffer.allocate(key.length);
    mEndKey.put(key);
    mEndKey.flip();

    mIndexFileOutStream.write(ByteBuffer.allocate(4).putInt(mDataFileLocation).array());
    mDataFileOutStream.write(ByteBuffer.allocate(4).putInt(key.length).array());
    mDataFileOutStream.write(key);
    mDataFileOutStream.write(ByteBuffer.allocate(4).putInt(value.length).array());
    mDataFileOutStream.write(value);
    mDataFileLocation += 4 + key.length + 4 + value.length;
    LOG.debug("PUT " + Utils.byteArrayToString(key) + " " + Utils.byteArrayToString(value));
  }

  @Override
  public String toString() {
    return new StringBuilder("PartitionSortedStorePartition(").append("CREATE ").append(CREATE)
        .append(" , STORE_PATH ").append(STORE_PATH).append(" , mPartitionPath ")
        .append(mPartitionPath).append(" , mDataFilePath ").append(mDataFilePath)
        .append(" , mIndexFilePath ").append(mIndexFilePath).append(")").toString();
  }
}
