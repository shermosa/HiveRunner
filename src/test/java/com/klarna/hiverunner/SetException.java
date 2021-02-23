/**
 * Copyright (C) 2013-2021 Klarna AB
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
package com.klarna.hiverunner;

import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;

import com.klarna.hiverunner.HiveRunnerExtension;
import com.klarna.hiverunner.HiveServerContainer;
import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.StandaloneHiveRunner;
import com.klarna.hiverunner.annotations.HiveSQL;

import java.nio.file.Paths;

@RunWith(StandaloneHiveRunner.class)
public class SetException {

  @HiveSQL(files = {})
  private HiveShell shell;
  private ExpectedSystemExit expectedSystemExit;

  //  @Test
//  public void testWithSuppressedOutput() {
//    //systemOutRule.mute();
//    System.out.print("some text");
//  }
//
//  @Test
//  public void testWithNormalOutput() {
//    System.out.print("some text");
//  }
//
  @Test
  public void test_without_set() {
    this.shell.execute(Paths.get("src/test/resources/test_without_set.hql"));
    expectedSystemExit.expectSystemExit();
  }

  @Test
  public void test_with_set() {
    this.shell.execute(Paths.get("src/test/resources/test_with_set.hql"));
    expectedSystemExit.expectSystemExit();
  }
}
