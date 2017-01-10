package com.hazelcast.hotrestart;

import com.hazelcast.internal.management.dto.ClusterHotRestartStatusDTO;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class NoopHotRestartServicesTest {

    @Test
    public void testNoOpHotRestartService() throws Exception {
        final NoOpHotRestartService service = new NoOpHotRestartService();
        service.backup();
        service.getBackupTaskStatus();
        service.interruptBackupTask();
        service.interruptLocalBackupTask();
        assertEquals(new BackupTaskStatus(BackupTaskState.NOT_STARTED, 0, 0), service.getBackupTaskStatus());
    }

    @Test
    public void testNoOpInternalHotRestartService() throws Exception {
        final NoopInternalHotRestartService service = new NoopInternalHotRestartService();
        service.handleExcludedMemberUuids(null, null);
        service.resetHotRestartData();

        assertFalse(service.triggerForceStart());
        assertFalse(service.triggerPartialStart());
        assertFalse(service.isMemberExcluded(null, null));
        assertEquals(0, service.getExcludedMemberUuids().size());
        final ClusterHotRestartStatusDTO expected = new ClusterHotRestartStatusDTO();
        final ClusterHotRestartStatusDTO dto = service.getCurrentClusterHotRestartStatus();
        assertEquals(expected.getDataRecoveryPolicy(), dto.getDataRecoveryPolicy());
        assertEquals(expected.getHotRestartStatus(), dto.getHotRestartStatus());
        assertEquals(expected.getRemainingDataLoadTimeMillis(), dto.getRemainingDataLoadTimeMillis());
        assertEquals(expected.getRemainingValidationTimeMillis(), dto.getRemainingValidationTimeMillis());
    }
}
