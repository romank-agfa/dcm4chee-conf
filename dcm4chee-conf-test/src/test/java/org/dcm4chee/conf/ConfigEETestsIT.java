/*
 * **** BEGIN LICENSE BLOCK *****
 *  Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 *  The contents of this file are subject to the Mozilla Public License Version
 *  1.1 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 *  for the specific language governing rights and limitations under the
 *  License.
 *
 *  The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 *  Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 *  The Initial Developer of the Original Code is
 *  Agfa Healthcare.
 *  Portions created by the Initial Developer are Copyright (C) 2015
 *  the Initial Developer. All Rights Reserved.
 *
 *  Contributor(s):
 *  See @authors listed below
 *
 *  Alternatively, the contents of this file may be used under the terms of
 *  either the GNU General Public License Version 2 or later (the "GPL"), or
 *  the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 *  in which case the provisions of the GPL or the LGPL are applicable instead
 *  of those above. If you wish to allow use of your version of this file only
 *  under the terms of either the GPL or the LGPL, and not to allow others to
 *  use your version of this file under the terms of the MPL, indicate your
 *  decision by deleting the provisions above and replace them with the notice
 *  and other provisions required by the GPL or the LGPL. If you do not delete
 *  the provisions above, a recipient may use your version of this file under
 *  the terms of any one of the MPL, the GPL or the LGPL.
 *
 *  ***** END LICENSE BLOCK *****
 */
package org.dcm4chee.conf;

import org.dcm4che3.conf.api.ConfigurationNotFoundException;
import org.dcm4che3.conf.api.DicomConfiguration;
import org.dcm4che3.conf.api.internal.DicomConfigurationManager;
import org.dcm4che3.conf.core.api.Configuration;
import org.dcm4che3.conf.core.api.ConfigurationException;
import org.dcm4che3.net.Device;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.FileAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.enterprise.inject.Any;
import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Roman K
 */
@RunWith(Arquillian.class)
public class ConfigEETestsIT {

    private static Logger LOG = LoggerFactory
            .getLogger(ConfigEETestsIT.class);


    @EJB
    MyConfyEJB myConfyEJB;

    @Deployment
    public static WebArchive createDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "test.war");

        war.addClass(ConfigEETestsIT.class);
        war.addClass(MyConfyEJB.class);
        war.addClass(MyConfigProducer.class);

        war.addAsManifestResource(new FileAsset(new File("src/test/resources/META-INF/MANIFEST.MF")), "MANIFEST.MF");
        war.addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");

        JavaArchive[] archs = Maven.resolver()
                .loadPomFromFile("testpom.xml")
                .importRuntimeAndTestDependencies()
                .resolve().withoutTransitivity()
                .as(JavaArchive.class);

        for (JavaArchive a : archs) {
            war.addAsLibrary(a);
        }

        war.as(ZipExporter.class).exportTo(
                new File("test.war"), true);
        return war;
    }

    @Inject
    @Any
    DicomConfigurationManager configurationManager;

    public DicomConfigurationManager getConfig() throws ConfigurationException {
        return configurationManager;
    }

    @Test
    public void rollbackTest() throws Exception {

        final DicomConfigurationManager config = getConfig();
        final Configuration storage = config.getConfigurationStorage();


        storage.removeNode("/dicomConfigurationRoot");

        storage.runBatch(new Configuration.ConfigBatch() {
            @Override
            public void run() {

                try {
                    config.persist(new Device("shouldWork"));
                } catch (ConfigurationException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        Assert.assertNotNull(config.findDevice("shouldWork"));

        try {
            storage.runBatch(new Configuration.ConfigBatch() {
                @Override
                public void run() {

                    try {
                        config.persist(new Device("shouldBeRolledBack"));
                        config.findDevice("shouldBeRolledBack");
                    } catch (ConfigurationException e) {
                        throw new RuntimeException(e);
                    }

                    throw new RuntimeException("Let's roll (back)!");
                }
            });
        } catch (Exception e) {
            // it's fine
            Assert.assertEquals(e.getCause().getMessage(), "Let's roll (back)!");
        }

        try {
            config.findDevice("shouldBeRolledBack");
            Assert.fail("device shouldBeRolledBack must not be there");
        } catch (ConfigurationNotFoundException e) {
            //it ok
        }

    }


    @Test
    public void lockTest() throws Exception {

        final DicomConfigurationManager config = getConfig();
        final Configuration storage = config.getConfigurationStorage();

        storage.removeNode("/dicomConfigurationRoot");

        final Semaphore masterSemaphore = new Semaphore(0);
        final Semaphore childSemaphore = new Semaphore(0);

        final AtomicInteger parallel = new AtomicInteger(0);

        Thread thread1 = new Thread() {
            @Override
            public void run() {
                myConfyEJB.execInTransaction(new Runnable() {
                    @Override
                    public void run() {

                        LOG.info("locking...");
                        storage.lock();
                        LOG.info("locked!");
                        parallel.addAndGet(1);


                        try {
                            config.persist(new Device("someDevice1"));
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }

                        LOG.info("double-locking...");
                        storage.lock();

                        try {
                            masterSemaphore.acquire();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                LOG.info("Committed");
                super.run();
            }
        };
        Thread thread2 = new Thread() {
            @Override
            public void run() {

                myConfyEJB.execInTransaction(new Runnable() {
                    @Override
                    public void run() {

                        LOG.info("locking...");
                        storage.lock();
                        LOG.info("locked!");
                        parallel.addAndGet(1);

                        try {
                            config.persist(new Device("someDevice2"));
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }

                        try {
                            masterSemaphore.acquire();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                    }
                });
                LOG.info("Committed");
                super.run();
            }
        };

        thread1.start();
        thread2.start();

        Thread.sleep(500);

        // make sure
        Assert.assertEquals(1, parallel.get());

        masterSemaphore.release();

        Thread.sleep(500);

        // make sure
        Assert.assertEquals(2, parallel.get());

        masterSemaphore.release();

        Thread.sleep(500);


    }

    @Test
    public void persistRootNodeTest() throws ConfigurationException {

        final DicomConfigurationManager config = getConfig();
        final Configuration storage = config.getConfigurationStorage();

        storage.removeNode("/dicomConfigurationRoot");

        config.persist(new Device("D1"));
        config.persist(new Device("D2"));

        Map<String, Object> configurationRoot = (Map<String, Object>) storage.getConfigurationNode("/dicomConfigurationRoot", null);

        storage.removeNode("/dicomConfigurationRoot");

        storage.persistNode("/dicomConfigurationRoot", configurationRoot, null);


        try {
            config.findDevice("D1");
        } catch (ConfigurationException e) {
            Assert.fail("Device should have been found");
        }

        storage.removeNode("/dicomConfigurationRoot");

        config.persist(new Device("D3"));
        config.persist(new Device("D4"));

        storage.persistNode("/dicomConfigurationRoot", configurationRoot, null);

        try {
            config.findDevice("D1");
        } catch (ConfigurationException e) {
            Assert.fail("Device should have been found");
        }

        try {
            config.findDevice("D3");
            Assert.fail("Device D3 shouldn't have been found");
        } catch (ConfigurationException e) {
        }


    }


    @Test
    // Only works with em.flush
    public void test2ConcurrentPersists() throws ConfigurationException, InterruptedException {
        final Semaphore masterSemaphore = new Semaphore(0);
        final Semaphore childSemaphore = new Semaphore(0);

        final DicomConfigurationManager config = getConfig();
        final Configuration storage = config.getConfigurationStorage();

        storage.removeNode("/dicomConfigurationRoot");

        config.persist(new Device("someDevice"));
        config.persist(new Device("someNotImportantDevice"));

        Thread thread1 = new Thread() {
            @Override
            public void run() {

                myConfyEJB.execInTransaction(new Runnable() {
                    @Override
                    public void run() {

                        try {
                            Device someDevice = config.findDevice("someDevice");
                            someDevice.setSoftwareVersions("5", "6", "7");
                            config.merge(someDevice);

                            // make sure em flush happens
                            config.findDevice("someNotImportantDevice");
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }

                        childSemaphore.release();
                        try {
                            masterSemaphore.acquire();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                    }
                });
                LOG.info("Committed");
                super.run();
            }
        };
        Thread thread2 = new Thread() {
            @Override
            public void run() {

                myConfyEJB.execInTransaction(new Runnable() {
                    @Override
                    public void run() {

                        try {
                            Device someDevice = config.findDevice("someDevice");
                            someDevice.setSoftwareVersions("1", "2", "3");
                            config.merge(someDevice);

                            // make sure em flush happens
                            config.findDevice("someNotImportantDevice");
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }

                        childSemaphore.release();
                        try {
                            masterSemaphore.acquire();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                    }
                });
                LOG.info("Committed");
                super.run();
            }
        };

        thread1.start();
        thread2.start();

        boolean doneIn5Seconds = childSemaphore.tryAcquire(2, 5, TimeUnit.SECONDS);

        try {
            Assert.assertFalse(doneIn5Seconds);
        } finally {
            LOG.info("Releasing children threads..");

            masterSemaphore.release(2);

            Thread.sleep(1000);

        }

    }

}
