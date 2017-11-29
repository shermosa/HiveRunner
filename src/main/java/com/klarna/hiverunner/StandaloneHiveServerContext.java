/*
 * Copyright 2013 Klarna AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klarna.hiverunner;

import com.klarna.hiverunner.config.HiveRunnerConfig;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.FsShellPermissions;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.ObjectStore;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.runtime.library.api.TezRuntimeConfiguration;
import org.hsqldb.jdbc.JDBCDriver;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;

import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.*;

/**
 * Responsible for common configuration for running the HiveServer within this JVM with zero external dependencies.
 * <p/>
 * This class contains a bunch of methods meant to be overridden in order to create slightly different contexts.
 * <p>
 * This context configures HiveServer for both mr and tez. There's nothing contradicting with those configurations so
 * they may coexist in order to allow test cases to alter execution engines within the same test by
 * E.g: 'set hive.execution.engine=tez;'.
 */
public class StandaloneHiveServerContext implements HiveServerContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(StandaloneHiveServerContext.class);

    private String metaStorageUrl;

    protected HiveConf hiveConf = new HiveConf();

    private final TemporaryFolder basedir;
    private final HiveRunnerConfig hiveRunnerConfig;

    public StandaloneHiveServerContext(TemporaryFolder basedir, HiveRunnerConfig hiveRunnerConfig) {
        this.basedir = basedir;
        this.hiveRunnerConfig = hiveRunnerConfig;
    }

    static {

        // Use a NOOP Meta store filter hook. For some reason it did not help to set it via the hiveconf.
        try {
            ClassPool cp = new ClassPool(true);
            CtClass ctClass = cp.get("org.apache.hadoop.hive.metastore.HiveMetaStoreClient");
            CtMethod ctMethod = ctClass.getDeclaredMethod("loadFilterHooks");
            ctMethod.setBody("{ return new org.apache.hadoop.hive.metastore.DefaultMetaStoreFilterHookImpl(conf); }");
            ctClass.toClass();
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        // Prohibit hadoop fs -chgrp from spamming sysout
        setFinalStaticField(FsShellPermissions.class, "allowedChars", "[-_./@a-zA-Z0-9\\\\]");

    }

    private static void setFinalStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set " + fieldName + "@" + clazz.getName() + " to '" + value + "'");
        }
    }


    @Override
    public final void init() {

        configureMiscHiveSettings(hiveConf);

        configureMetaStore(hiveConf);

        configureMrExecutionEngine(hiveConf);

        configureTezExecutionEngine(hiveConf);

        configureJavaSecurityRealm(hiveConf);

        configureSupportConcurrency(hiveConf);

        configureFileSystem(basedir, hiveConf);

        configureAssertionStatus(hiveConf);

        overrideHiveConf(hiveConf);

    }

    protected void configureMiscHiveSettings(HiveConf hiveConf) {


        hiveConf.setBoolean("mapreduce.client.genericoptionsparser.used", true);
        hiveConf.setBoolVar(HIVE_AUTHORIZATION_ENABLED, false);
        hiveConf.setVar(HIVE_AUTHORIZATION_MANAGER, "org.apache.hive.hcatalog.storagehandler.DummyHCatAuthProvider");

        hiveConf.setBoolVar(HIVESTATSAUTOGATHER, false);

        // Turn of dependency to calcite library
        hiveConf.setBoolVar(HIVE_CBO_ENABLED, false);

        // Disable to get rid of clean up exception when stopping the Session.
        hiveConf.setBoolVar(HIVE_SERVER2_LOGGING_OPERATION_ENABLED, false);

        hiveConf.setVar(HADOOPBIN, "NO_BIN!");
    }

    protected void overrideHiveConf(HiveConf hiveConf) {
        for (Map.Entry<String, String> hiveConfEntry : hiveRunnerConfig.getHiveConfSystemOverride().entrySet()) {
            hiveConf.set(hiveConfEntry.getKey(), hiveConfEntry.getValue());
        }
    }

    protected void configureMrExecutionEngine(HiveConf conf) {

        /*
        * Switch off all optimizers otherwise we didn't
        * manage to contain the map reduction within this JVM.
        */
        conf.setBoolVar(HIVE_INFER_BUCKET_SORT, false);
        conf.setBoolVar(HIVEMETADATAONLYQUERIES, false);
        conf.setBoolVar(HIVEOPTINDEXFILTER, false);
        conf.setBoolVar(HIVECONVERTJOIN, false);
        conf.setBoolVar(HIVESKEWJOIN, false);

        // Defaults to a 1000 millis sleep in. We can speed up the tests a bit by setting this to 1 millis instead.
        // org.apache.hadoop.hive.ql.exec.mr.HadoopJobExecHelper.
        conf.setLongVar(HiveConf.ConfVars.HIVECOUNTERSPULLINTERVAL, 1L);

        conf.setBoolVar(HiveConf.ConfVars.HIVE_RPC_QUERY_PLAN, true);
    }

    protected void configureTezExecutionEngine(HiveConf conf) {
        /*
        Tez local mode settings
         */
        conf.setBoolean(TezConfiguration.TEZ_LOCAL_MODE, true);
        conf.set("fs.defaultFS", "file:///");
        conf.setBoolean(TezRuntimeConfiguration.TEZ_RUNTIME_OPTIMIZE_LOCAL_FETCH, true);

        /*
        Set to be able to run tests offline
         */
        conf.set(TezConfiguration.TEZ_AM_DISABLE_CLIENT_VERSION_CHECK, "true");

        /*
        General attempts to strip of unnecessary functionality to speed up test execution and increase stability
         */
        conf.set(TezConfiguration.TEZ_AM_USE_CONCURRENT_DISPATCHER, "false");
        conf.set(TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED, "false");
        conf.set(TezConfiguration.DAG_RECOVERY_ENABLED, "false");
        conf.set(TezConfiguration.TEZ_TASK_GET_TASK_SLEEP_INTERVAL_MS_MAX, "1");
        conf.set(TezConfiguration.TEZ_AM_WEBSERVICE_ENABLE, "false");
        conf.set(TezConfiguration.DAG_RECOVERY_ENABLED, "false");
        conf.set(TezConfiguration.TEZ_AM_NODE_BLACKLISTING_ENABLED, "false");
    }

    protected void configureJavaSecurityRealm(HiveConf hiveConf) {
        // These three properties gets rid of: 'Unable to load realm info from SCDynamicStore'
        // which seems to have a timeout of about 5 secs.
        System.setProperty("java.security.krb5.realm", "");
        System.setProperty("java.security.krb5.kdc", "");
        System.setProperty("java.security.krb5.conf", "/dev/null");
    }

    protected void configureAssertionStatus(HiveConf conf) {
        ClassLoader.getSystemClassLoader().setPackageAssertionStatus("org.apache.hadoop.hive.serde2.objectinspector",
                false);
    }

    protected void configureSupportConcurrency(HiveConf conf) {
        conf.setBoolVar(HIVE_SUPPORT_CONCURRENCY, false);
    }

    protected void configureMetaStore(HiveConf conf) {
//        configureHsqldbMetastore(conf);
        configureDerbyMetastore(conf);
    }

    private void configureDerbyMetastore(HiveConf conf) {

        ObjectStore.setSchemaVerified(true);

//        metaStorageUrl = "jdbc:derby:;databaseName=target/metastore_db_" + UUID.randomUUID().toString() + ";create=true";
        metaStorageUrl = "jdbc:derby:memory:metastore_db" + UUID.randomUUID() + ";create=true";

        // No pooling needed. This will save us a lot of threads
        conf.set("datanucleus.connectionPoolingType", "None");
        conf.setBoolean("datanucleus.schema.autoCreateTables", true);


        conf.setBoolVar(METASTORE_VALIDATE_CONSTRAINTS, true);
        conf.setBoolVar(METASTORE_VALIDATE_COLUMNS, true);
        conf.setBoolVar(METASTORE_VALIDATE_TABLES, true);

        conf.setVar(METASTORECONNECTURLKEY, metaStorageUrl);


    }

    protected void configureFileSystem(TemporaryFolder basedir, HiveConf conf) {
        createAndSetFolderProperty(METASTOREWAREHOUSE, "warehouse", conf, basedir);
        createAndSetFolderProperty(SCRATCHDIR, "scratchdir", conf, basedir);
        createAndSetFolderProperty(LOCALSCRATCHDIR, "localscratchdir", conf, basedir);
        createAndSetFolderProperty(HIVEHISTORYFILELOC, "tmp", conf, basedir);

        conf.setBoolVar(HIVE_WAREHOUSE_SUBDIR_INHERIT_PERMS, true);

        createAndSetFolderProperty("hadoop.tmp.dir", "hadooptmp", conf, basedir);
        createAndSetFolderProperty("test.log.dir", "logs", conf, basedir);



        /*
            Tez specific configurations below
         */
        /*
            Tez will upload a hive-exec.jar to this location.
            It looks like it will do this only once per test suite so it makes sense to keep this in a central location
            rather than in the tmp dir of each test.
         */
        File installation_dir = newFolder(getBaseDir(), "tez_installation_dir");

        conf.setVar(HiveConf.ConfVars.HIVE_JAR_DIRECTORY, installation_dir.getAbsolutePath());
        conf.setVar(HiveConf.ConfVars.HIVE_USER_INSTALL_DIR, installation_dir.getAbsolutePath());

    }

    File newFolder(TemporaryFolder basedir, String folder) {
        try {
            File newFolder = basedir.newFolder(folder);
            FileUtil.setPermission(newFolder, FsPermission.getDirDefault());
            return newFolder;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create tmp dir: " + e.getMessage(), e);
        }
    }

    public HiveConf getHiveConf() {
        return hiveConf;
    }

    @Override
    public TemporaryFolder getBaseDir() {
        return basedir;
    }

    protected final void createAndSetFolderProperty(HiveConf.ConfVars var, String folder, HiveConf conf,
                                                    TemporaryFolder basedir) {
        conf.setVar(var, newFolder(basedir, folder).getAbsolutePath());
    }

    protected final void createAndSetFolderProperty(String key, String folder, HiveConf conf, TemporaryFolder basedir) {
        conf.set(key, newFolder(basedir, folder).getAbsolutePath());
    }

}
