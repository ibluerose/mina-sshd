/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.util.test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.util.Pair;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;

/**
 * @see <A HREF="https://issues.apache.org/jira/browse/SSHD-764">SSHD-764</A>
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class JUnit4SingleInstanceClassRunner extends BlockJUnit4ClassRunner {
    private final AtomicReference<Pair<Class<?>, Object>> testHolder = new AtomicReference<>();

    public JUnit4SingleInstanceClassRunner(Class<?> klass) throws InitializationError {
        super(klass);
    }

    @Override
    protected Object createTest() throws Exception {
        Pair<Class<?>, Object> lastTest = testHolder.get();
        Class<?> lastTestClass = (lastTest == null) ? null : lastTest.getKey();
        TestClass curTest = getTestClass();
        Class<?> curTestClass = curTest.getJavaClass();
        if (curTestClass == lastTestClass) {
            return lastTest.getValue();
        }

        Constructor<?> ctor = curTest.getOnlyConstructor();
        Object instance = ctor.newInstance();
        testHolder.set(new Pair<>(curTestClass, instance));
        return instance;
    }
}
