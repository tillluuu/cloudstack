// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.image.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObjectInStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;

import com.cloud.storage.DataStoreRole;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.UpdateBuilder;

@Component
public class SnapshotDataStoreDaoImpl extends GenericDaoBase<SnapshotDataStoreVO, Long> implements SnapshotDataStoreDao {
    private static final Logger s_logger = Logger.getLogger(SnapshotDataStoreDaoImpl.class);
    private SearchBuilder<SnapshotDataStoreVO> updateStateSearch;
    private SearchBuilder<SnapshotDataStoreVO> storeSearch;
    private SearchBuilder<SnapshotDataStoreVO> destroyedSearch;
    private SearchBuilder<SnapshotDataStoreVO> cacheSearch;
    private SearchBuilder<SnapshotDataStoreVO> snapshotSearch;
    private SearchBuilder<SnapshotDataStoreVO> storeSnapshotSearch;
    private SearchBuilder<SnapshotDataStoreVO> snapshotIdSearch;
    private final String parentSearch = "select store_id, store_role, snapshot_id from cloud.snapshot_store_ref where store_id = ? " +
            " and store_role = ? and volume_id = ? and state = 'Ready'" +
            " order by created DESC " +
            " limit 1";



    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);

        // Note that snapshot_store_ref stores snapshots on primary as well as
        // those on secondary, so we need to
        // use (store_id, store_role) to search
        storeSearch = createSearchBuilder();
        storeSearch.and("store_id", storeSearch.entity().getDataStoreId(), SearchCriteria.Op.EQ);
        storeSearch.and("store_role", storeSearch.entity().getRole(), SearchCriteria.Op.EQ);
        storeSearch.and("state", storeSearch.entity().getState(), SearchCriteria.Op.NEQ);
        storeSearch.done();

        destroyedSearch = createSearchBuilder();
        destroyedSearch.and("store_id", destroyedSearch.entity().getDataStoreId(), SearchCriteria.Op.EQ);
        destroyedSearch.and("store_role", destroyedSearch.entity().getRole(), SearchCriteria.Op.EQ);
        destroyedSearch.and("state", destroyedSearch.entity().getState(), SearchCriteria.Op.EQ);
        destroyedSearch.done();

        cacheSearch = createSearchBuilder();
        cacheSearch.and("store_id", cacheSearch.entity().getDataStoreId(), SearchCriteria.Op.EQ);
        cacheSearch.and("store_role", cacheSearch.entity().getRole(), SearchCriteria.Op.EQ);
        cacheSearch.and("state", cacheSearch.entity().getState(), SearchCriteria.Op.NEQ);
        cacheSearch.and("ref_cnt", cacheSearch.entity().getRefCnt(), SearchCriteria.Op.NEQ);
        cacheSearch.done();

        updateStateSearch = this.createSearchBuilder();
        updateStateSearch.and("id", updateStateSearch.entity().getId(), Op.EQ);
        updateStateSearch.and("state", updateStateSearch.entity().getState(), Op.EQ);
        updateStateSearch.and("updatedCount", updateStateSearch.entity().getUpdatedCount(), Op.EQ);
        updateStateSearch.done();

        snapshotSearch = createSearchBuilder();
        snapshotSearch.and("snapshot_id", snapshotSearch.entity().getSnapshotId(), SearchCriteria.Op.EQ);
        snapshotSearch.and("store_role", snapshotSearch.entity().getRole(), SearchCriteria.Op.EQ);
        snapshotSearch.done();

        storeSnapshotSearch = createSearchBuilder();
        storeSnapshotSearch.and("snapshot_id", storeSnapshotSearch.entity().getSnapshotId(), SearchCriteria.Op.EQ);
        storeSnapshotSearch.and("store_id", storeSnapshotSearch.entity().getDataStoreId(), SearchCriteria.Op.EQ);
        storeSnapshotSearch.and("store_role", storeSnapshotSearch.entity().getRole(), SearchCriteria.Op.EQ);
        storeSnapshotSearch.done();

        snapshotIdSearch = createSearchBuilder();
        snapshotIdSearch.and("snapshot_id", snapshotIdSearch.entity().getSnapshotId(), SearchCriteria.Op.EQ);
        snapshotIdSearch.done();

        return true;
    }

    @Override
    public boolean updateState(State currentState, Event event, State nextState, DataObjectInStore vo, Object data) {
        SnapshotDataStoreVO dataObj = (SnapshotDataStoreVO) vo;
        Long oldUpdated = dataObj.getUpdatedCount();
        Date oldUpdatedTime = dataObj.getUpdated();

        SearchCriteria<SnapshotDataStoreVO> sc = updateStateSearch.create();
        sc.setParameters("id", dataObj.getId());
        sc.setParameters("state", currentState);
        sc.setParameters("updatedCount", dataObj.getUpdatedCount());

        dataObj.incrUpdatedCount();

        UpdateBuilder builder = getUpdateBuilder(dataObj);
        builder.set(dataObj, "state", nextState);
        builder.set(dataObj, "updated", new Date());

        int rows = update(dataObj, sc);
        if (rows == 0 && s_logger.isDebugEnabled()) {
            SnapshotDataStoreVO dbVol = findByIdIncludingRemoved(dataObj.getId());
            if (dbVol != null) {
                StringBuilder str = new StringBuilder("Unable to update ").append(dataObj.toString());
                str.append(": DB Data={id=").append(dbVol.getId()).append("; state=").append(dbVol.getState())
                .append("; updatecount=").append(dbVol.getUpdatedCount()).append(";updatedTime=")
                .append(dbVol.getUpdated());
                str.append(": New Data={id=").append(dataObj.getId()).append("; state=").append(nextState)
                .append("; event=").append(event).append("; updatecount=").append(dataObj.getUpdatedCount())
                .append("; updatedTime=").append(dataObj.getUpdated());
                str.append(": stale Data={id=").append(dataObj.getId()).append("; state=").append(currentState)
                .append("; event=").append(event).append("; updatecount=").append(oldUpdated)
                .append("; updatedTime=").append(oldUpdatedTime);
            } else {
                s_logger.debug("Unable to update objectIndatastore: id=" + dataObj.getId()
                        + ", as there is no such object exists in the database anymore");
            }
        }
        return rows > 0;
    }

    @Override
    public List<SnapshotDataStoreVO> listByStoreId(long id, DataStoreRole role) {
        SearchCriteria<SnapshotDataStoreVO> sc = storeSearch.create();
        sc.setParameters("store_id", id);
        sc.setParameters("store_role", role);
        sc.setParameters("state", ObjectInDataStoreStateMachine.State.Destroyed);
        return listBy(sc);
    }

    @Override
    public void deletePrimaryRecordsForStore(long id, DataStoreRole role) {
        SearchCriteria<SnapshotDataStoreVO> sc = storeSearch.create();
        sc.setParameters("store_id", id);
        sc.setParameters("store_role", role);
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        remove(sc);
        txn.commit();
    }

    @Override
    public void deleteSnapshotRecordsOnPrimary() {
        SearchCriteria<SnapshotDataStoreVO> sc = storeSearch.create();
        sc.setParameters("store_role", DataStoreRole.Primary);
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        remove(sc);
        txn.commit();
    }

    @Override
    public SnapshotDataStoreVO findByStoreSnapshot(DataStoreRole role, long storeId, long snapshotId) {
        SearchCriteria<SnapshotDataStoreVO> sc = storeSnapshotSearch.create();
        sc.setParameters("store_id", storeId);
        sc.setParameters("snapshot_id", snapshotId);
        sc.setParameters("store_role", role);
        return findOneBy(sc);
    }

    @Override
    @DB
    public SnapshotDataStoreVO findParent(DataStoreRole role, Long storeId, Long volumeId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = txn.prepareStatement(parentSearch);
            pstmt.setLong(1, storeId);
            pstmt.setString(2, role.toString());
            pstmt.setLong(3, volumeId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long sid = rs.getLong(1);
                String rl = rs.getString(2);
                long snid = rs.getLong(3);
                return findByStoreSnapshot(role, sid, snid);
            }
        } catch (SQLException e) {
            s_logger.debug("Failed to find parent snapshot: " + e.toString());
        }
        return null;
    }

    @Override
    public SnapshotDataStoreVO findBySnapshot(long snapshotId, DataStoreRole role) {
        SearchCriteria<SnapshotDataStoreVO> sc = snapshotSearch.create();
        sc.setParameters("snapshot_id", snapshotId);
        sc.setParameters("store_role", role);
        return findOneBy(sc);
    }

    @Override
    public List<SnapshotDataStoreVO> findBySnapshotId(long snapshotId) {
        SearchCriteria<SnapshotDataStoreVO> sc = snapshotIdSearch.create();
        sc.setParameters("snapshot_id", snapshotId);
        return listBy(sc);
    }

    @Override
    public List<SnapshotDataStoreVO> listDestroyed(long id) {
        SearchCriteria<SnapshotDataStoreVO> sc = destroyedSearch.create();
        sc.setParameters("store_id", id);
        sc.setParameters("store_role", DataStoreRole.Image);
        sc.setParameters("state", ObjectInDataStoreStateMachine.State.Destroyed);
        return listBy(sc);
    }

    @Override
    public List<SnapshotDataStoreVO> listActiveOnCache(long id) {
        SearchCriteria<SnapshotDataStoreVO> sc = cacheSearch.create();
        sc.setParameters("store_id", id);
        sc.setParameters("store_role", DataStoreRole.ImageCache);
        sc.setParameters("state", ObjectInDataStoreStateMachine.State.Destroyed);
        sc.setParameters("ref_cnt", 0);
        return listBy(sc);
    }

    @Override
    public void duplicateCacheRecordsOnRegionStore(long storeId) {
        // find all records on image cache
        SearchCriteria<SnapshotDataStoreVO> sc = cacheSearch.create();
        sc.setParameters("store_id", storeId);
        sc.setParameters("destroyed", false);
        List<SnapshotDataStoreVO> snapshots = listBy(sc);
        // create an entry for each record, but with empty install path since the content is not yet on region-wide store yet
        if (snapshots != null) {
            for (SnapshotDataStoreVO snap : snapshots) {
                SnapshotDataStoreVO ss = new SnapshotDataStoreVO();
                ss.setSnapshotId(snap.getId());
                ss.setDataStoreId(storeId);
                ss.setRole(DataStoreRole.Image);
                ss.setVolumeId(snap.getVolumeId());
                ss.setParentSnapshotId(snap.getParentSnapshotId());
                ss.setState(snap.getState());
                ss.setSize(snap.getSize());
                ss.setPhysicalSize(snap.getPhysicalSize());
                ss.setRefCnt(snap.getRefCnt() + 1); // increase ref_cnt so that this will not be recycled before the content is pushed to region-wide store
                persist(ss);
            }
        }

    }

    @Override
    public List<SnapshotDataStoreVO> listOnCache(long snapshotId) {
        SearchCriteria<SnapshotDataStoreVO> sc = storeSnapshotSearch.create();
        sc.setParameters("snapshot_id", snapshotId);
        sc.setParameters("store_role", DataStoreRole.ImageCache);
        return search(sc, null);
    }

}
