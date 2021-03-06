package com.taobao.adfs.distributed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taobao.adfs.distributed.DistributedOperation.OperandKey;
import com.taobao.adfs.util.Utilities;

/**
 * @author <a href=mailto:zhangwei.yangjie@gmail.com/jiwan@taobao.com>zhangwei/jiwan</a>
 */
public class DistributedOperationQueue {
  public static final Logger logger = LoggerFactory.getLogger(DistributedOperationQueue.class);
  Map<OperandKey, ArrayList<DistributedOperation>> operationMapByKey =
      new HashMap<OperandKey, ArrayList<DistributedOperation>>();
  Map<Long, ArrayList<DistributedOperation>> operationByThreadId = new HashMap<Long, ArrayList<DistributedOperation>>();
  Set<OperandKey> operationLock = new HashSet<OperandKey>();
  volatile boolean allowAdd = true;

  public boolean setAllowAdd(boolean allowAdd) {
    return this.allowAdd = allowAdd;
  }

  public synchronized DistributedOperation[] lockAndGetOperations(long threadId) {
    if (!allowAdd) return null;
    DistributedOperation[] operations = getOperations(threadId);
    if (operations == null || operations.length == 0) return null;
    DistributedOperation[] notWrittedOperations = getNotWrittenOperations(operations);
    lockBuckets(notWrittedOperations);
    return notWrittedOperations;
  }

  public synchronized void deleteAndUnlockOperations(DistributedOperation[] operations) {
    markOperationsAreWritten(operations);
    deleteOperations(operations);
    unlockBuckets(operations);
  }
  public synchronized void clear() {
    operationByThreadId.clear();
    operationMapByKey.clear();
    operationLock.clear();
  }

  /**
   * get operations in queue by threadId
   */
  public synchronized void add(DistributedOperation distributedOperation) {
    addByThreadId(distributedOperation, Thread.currentThread().getId());
  }
  
  public synchronized void addByThreadId(DistributedOperation distributedOperation, long threadId) {
    if (!allowAdd) return;
    // add to map(operation's key, operations)
    ArrayList<DistributedOperation> operationListByKey = operationMapByKey.get(distributedOperation.getKey());
    if (operationListByKey == null) {
      operationListByKey = new ArrayList<DistributedOperation>();
      operationMapByKey.put(distributedOperation.getKey(), operationListByKey);
    }
    operationListByKey.add(distributedOperation);

    // add to map(threadId, operations)
    ArrayList<DistributedOperation> operationListByThreadId = operationByThreadId.get(threadId);
    if (operationListByThreadId == null) {
      operationListByThreadId = new ArrayList<DistributedOperation>();
      operationByThreadId.put(threadId, operationListByThreadId);
    }
    operationListByThreadId.add(distributedOperation);
  }

  /**
   * get operations in queue by threadId
   */
  DistributedOperation[] getOperations(long threadId) {
    ArrayList<DistributedOperation> operationListByThreadId = operationByThreadId.get(threadId);
    if (operationListByThreadId == null) return null;
    int index = -1;
    DistributedOperation operation = null;
    ArrayList<DistributedOperation> operationListByKey = null;
    Map<OperandKey, Integer> counter = new LinkedHashMap<OperandKey, Integer>();
    Integer last = -1;
    for (int i = 0, len = operationListByThreadId.size(); i < len; i++) {
      operation = operationListByThreadId.get(i);
      operationListByKey = operationMapByKey.get(operation.getKey());
      if(operationListByKey==null) continue;
      index = operationListByKey.indexOf(operation);
      last = counter.get(operation.getKey());
      if (last == null || index > last) counter.put(operation.getKey(), index);
    }
    ArrayList<DistributedOperation> result = new ArrayList<DistributedOperation>();
    for (Map.Entry<OperandKey, Integer> entry : counter.entrySet()) {
      result.addAll(operationMapByKey.get(entry.getKey()).subList(0, entry.getValue() + 1));
    }
    return result.toArray(new DistributedOperation[result.size()]);
  }

  /**
   * lock operation-buckets by operations
   */
  void lockBuckets(DistributedOperation... operations) {
    Set<OperandKey> keySet = new LinkedHashSet<OperandKey>();
    for (DistributedOperation operation : operations) {
      keySet.add(operation.getKey());
    }
    //first detect all lock required avaliable
    for (OperandKey operationKey : keySet) {
      try {
        while (operationLock.contains(operationKey)) {
          wait();
        }
      } catch (Throwable t) {
        Utilities.logDebug(logger, t);
        throw new RuntimeException(t);
      }
    }
    // then lock all operation one time to avoid deadlock
    for (OperandKey operationKey : keySet) {
      operationLock.add(operationKey);
    }
    logger.debug(Thread.currentThread().getName()+" get lock");
  }

  /**
   * get not written operations, ignore those behind operations if ignoreBehind is true
   */
  private DistributedOperation[] getNotWrittenOperations(DistributedOperation... operations) {
    if (operations == null) return null;
    List<DistributedOperation> operationList = new ArrayList<DistributedOperation>();
    for (DistributedOperation operation : operations) {
      if (!operation.written) operationList.add(operation);
    }
    return operationList.toArray(new DistributedOperation[operationList.size()]);
  }

  /**
   * delete operations in queue
   */
  private void deleteOperations(DistributedOperation... operations) {
    operationByThreadId.remove(Thread.currentThread().getId());
    if (operations == null) return;
    List<DistributedOperation> operationList = null;
    // delete from operationByKey
    for (DistributedOperation operation : operations) {
      operationList = operationMapByKey.get(operation.getKey());
      if(operationList == null) continue;
      operationList.remove(operation);
      if (operationList.isEmpty()) operationMapByKey.remove(operation.getKey());
    }
  }

  /**
   * mark operations are written
   */
  private void markOperationsAreWritten(DistributedOperation... operations) {
    if (operations == null) return;
    for (DistributedOperation operation : operations) {
      if (operation == null) continue;
      operation.setWritten(true);
    }
  }

  /**
   * unlock operation-buckets by operations
   */
  void unlockBuckets(DistributedOperation... operations) {
    if (operations == null) return;
    Set<OperandKey> keySet = new LinkedHashSet<OperandKey>();
    for (DistributedOperation operation : operations){
      keySet.add(operation.getKey());
     }
    for (DistributedOperation operation : operations) {
      operationLock.remove(operation.getKey());
    }
    logger.debug(Thread.currentThread().getName()+" release lock");
    notifyAll();
  }
}
