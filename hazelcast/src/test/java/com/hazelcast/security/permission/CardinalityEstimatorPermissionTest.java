/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.security.permission;

import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.security.Permission;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class CardinalityEstimatorPermissionTest extends AbstractPermissionTest {

    @Override
    protected String[] getActions() {
        return new String[]{
                "create",
                "read",
                "modify",
                "destroy"
        };
    }

    @Override
    protected Permission createPermission(String name, String... actions) {
        return new CardinalityEstimatorPermission(name, actions);
    }

    @Test
    @Override
    public void willReturnFalseForNoPermOnListen() {
        // listen is not supported
    }

    @Test
    @Override
    public void willReturnFalseForNoPermOnPut() {
        // put is not supported
    }
}
