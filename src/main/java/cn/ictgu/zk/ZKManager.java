package cn.ictgu.zk;

import cn.ictgu.config.ZookeeperProperties;
import lombok.extern.log4j.Log4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ZK Manager
 * Created by Silence on 2016/12/19.
 */
@Log4j
public class ZKManager {

  private ZookeeperProperties properties;
  private ZooKeeper zk;
  private List<ACL> acl = new ArrayList<>();
  private boolean isCheckParentPath = true;

  public ZKManager(ZookeeperProperties properties) throws Exception {
    this.properties = properties;
    this.connect();
  }

  private void connect() throws Exception {
    CountDownLatch connectionLatch = new CountDownLatch(1);
    createZookeeper(connectionLatch);
    connectionLatch.await(10, TimeUnit.SECONDS);
  }

  private void createZookeeper(final CountDownLatch connectionLatch) throws Exception {
    zk = new ZooKeeper(properties.getHost() + ":" + properties.getPort(), properties.getTimeout(), new Watcher() {
      public void process(WatchedEvent event) {
        sessionEvent(connectionLatch, event);
      }
    });
    String authString = properties.getUsername() + ":" + properties.getPassword();
    this.isCheckParentPath = true;
    zk.addAuthInfo("digest", authString.getBytes());
    acl.clear();
    acl.add(new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest(authString))));
    acl.add(new ACL(ZooDefs.Perms.READ, Ids.ANYONE_ID_UNSAFE));
  }

  private void sessionEvent(CountDownLatch connectionLatch, WatchedEvent event) {
    if (event.getState() == KeeperState.SyncConnected) {
      log.info("Zookeeper 连接成功！");
      connectionLatch.countDown();
    } else if (event.getState() == KeeperState.Expired) {
      log.error("会话超时，等待重新建立 Zookeeper 连接...");
      try {
        reConnection();
      } catch (Exception e) {
        log.error(e.getMessage(), e);
      }
    } // Disconnected：Zookeeper会自动处理Disconnected状态重连
    else if (event.getState() == KeeperState.Disconnected) {
      log.info("any_schedule Disconnected，等待重新建立 Zookeeper 连接...");
      try {
        reConnection();
      } catch (Exception e) {
        log.error(e.getMessage(), e);
      }
    } else {
      log.info("any_schedule 会话有其他状态的值，event.getState() =" + event.getState() + ", event  value=" + event.toString());
      connectionLatch.countDown();
    }
  }

  // 重连zookeeper
  synchronized void reConnection() throws Exception {
    if (this.zk != null) {
      this.zk.close();
      this.zk = null;
      this.connect();
    }
  }

  public void initial() throws Exception {
    //当zk状态正常后才能调用
    if (zk.exists(properties.getRootPath(), false) == null) {
      ZKTools.createPath(zk, properties.getRootPath(), CreateMode.PERSISTENT, acl);
      if (isCheckParentPath) {
        checkParent(zk, properties.getRootPath());
      }
      //设置版本信息
      zk.setData(properties.getRootPath(), Version.getVersion().getBytes(), -1);
    } else {
      //先校验父亲节点，本身是否已经是schedule的目录
      if (isCheckParentPath) {
        checkParent(zk, properties.getRootPath());
      }
      byte[] value = zk.getData(properties.getRootPath(), false, null);
      if (value == null) {
        zk.setData(properties.getRootPath(), Version.getVersion().getBytes(), -1);
      } else {
        String dataVersion = new String(value);
        if (!Version.isCompatible(dataVersion)) {
          throw new Exception("AnySchedule程序版本 " + Version.getVersion() + " 不兼容Zookeeper中的数据版本 " + dataVersion);
        }
        log.info("当前的程序版本:" + Version.getVersion() + " 数据版本: " + dataVersion);
      }
    }
  }

  private static void checkParent(ZooKeeper zk, String path) throws Exception {
    String[] list = path.split("/");
    String zkPath = "";
    for (int i = 0; i < list.length - 1; i++) {
      String str = list[i];
      if (!str.equals("")) {
        zkPath = zkPath + "/" + str;
        if (zk.exists(zkPath, false) != null) {
          byte[] value = zk.getData(zkPath, false, null);
          if (value != null) {
            String tmpVersion = new String(value);
            if (tmpVersion.contains("taobao-pamirs-schedule-")) {
              throw new Exception("\"" + zkPath
                                  + "\"  is already a schedule instance's root directory, its any subdirectory cannot as the root directory of others");
            }
          }
        }
      }
    }
  }

  public void close() throws InterruptedException {
    log.info("关闭zookeeper连接");
    if (zk == null) {
      return;
    }
    this.zk.close();
  }

  public boolean checkZookeeperState() throws Exception {
    return zk != null && zk.getState() == ZooKeeper.States.CONNECTED;
  }

  public ZooKeeper getZooKeeper() throws Exception {
    if (!this.checkZookeeperState()) {
      log.info("重新连接Zookeeper");
      reConnection();
    }
    return this.zk;
  }

  String getRootPath() {
    return this.properties.getRootPath();
  }

  List<ACL> getAcl() {
    return this.acl;
  }

  public String getConnectStr() {
    return properties.getHost();
  }

}
