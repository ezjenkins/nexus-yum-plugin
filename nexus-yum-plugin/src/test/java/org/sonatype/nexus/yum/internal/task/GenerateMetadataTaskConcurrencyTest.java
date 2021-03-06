/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2013 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.yum.internal.task;

import static java.io.File.pathSeparator;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.yum.internal.task.GenerateMetadataTask.ID;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.proxy.maven.MavenRepository;
import org.sonatype.nexus.proxy.maven.routing.Manager;
import org.sonatype.nexus.proxy.registry.RepositoryRegistry;
import org.sonatype.nexus.proxy.repository.HostedRepository;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.repository.RepositoryKind;
import org.sonatype.nexus.rest.RepositoryURLBuilder;
import org.sonatype.nexus.scheduling.NexusScheduler;
import org.sonatype.nexus.yum.Yum;
import org.sonatype.nexus.yum.YumRegistry;
import org.sonatype.nexus.yum.YumRepository;
import org.sonatype.nexus.yum.internal.RepoMD;
import org.sonatype.nexus.yum.internal.RpmScanner;
import org.sonatype.scheduling.ScheduledTask;
import org.sonatype.sisu.goodies.eventbus.EventBus;

import com.google.code.tempusfugit.temporal.Condition;

public class GenerateMetadataTaskConcurrencyTest
    extends GenerateMetadataTaskTestSupport
{

    private static final String RPM_NAME_2 = "hallomommy";

    private static final String RPM_NAME_1 = "hallodaddy";

    public static final int PARALLEL_THREAD_COUNT = 5;

    public static final Logger LOG = LoggerFactory.getLogger( GenerateMetadataTaskConcurrencyTest.class );

    private static final int MAX_PARALLEL_SCHEDULER_THREADS = 20;

    @Inject
    private NexusScheduler nexusScheduler;

    @Inject
    private YumRegistry yumRegistry;

    private final Set<String> threadNames = new HashSet<String>();

    @After
    public void waitForAllTasks()
        throws TimeoutException, InterruptedException
    {
        waitFor( new Condition()
        {
            @Override
            public boolean isSatisfied()
            {
                return nexusScheduler.getActiveTasks().isEmpty();
            }
        } );
    }

    @Test
    public void shouldExecuteSeveralThreadInParallel()
        throws Exception
    {
        List<ScheduledTask<?>> futures = new ArrayList<ScheduledTask<?>>();

        for ( int repositoryId = 0; repositoryId < PARALLEL_THREAD_COUNT; repositoryId++ )
        {
            futures.add( nexusScheduler.submit( ID, createYumRepositoryTask( repositoryId ) ) );
        }

        waitFor( futures );
    }

    @Test
    public void shouldReuseQueuedTaskOfTheSameType()
        throws Exception
    {
        final File tmpDir = copyToTempDir( rpmsDir() );

        final MavenRepository repository = mock( MavenRepository.class );
        when( repository.getId() ).thenReturn( "REPO" );
        when( repository.getLocalUrl() ).thenReturn( tmpDir.getAbsolutePath() );
        when( repository.getProviderRole() ).thenReturn( Repository.class.getName() );
        when( repository.getProviderHint() ).thenReturn( "maven2" );
        final RepositoryKind repositoryKind = mock( RepositoryKind.class );
        when( repositoryKind.isFacetAvailable( HostedRepository.class ) ).thenReturn( true );
        when( repository.getRepositoryKind() ).thenReturn( repositoryKind );

        final File rpm1 = createDummyRpm( RPM_NAME_1, "1", new File( tmpDir, "rpm1" ) );
        final File rpm2 = createDummyRpm( RPM_NAME_2, "2", new File( tmpDir, "rpm2" ) );

        // given executions blocking all thread of the scheduler
        final List<ScheduledTask<?>> futures = new ArrayList<ScheduledTask<?>>();
        for ( int index = 0; index < MAX_PARALLEL_SCHEDULER_THREADS; index++ )
        {
            futures.add( nexusScheduler.submit( "WaitTask", nexusScheduler.createTaskInstance( WaitTask.class ) ) );
        }
        final Yum yum = yumRegistry.register( repository );

        // when
        final String file1 = "rpm1/" + rpm1.getName();
        final String file2 = "rpm2/" + rpm2.getName();

        final ScheduledTask<YumRepository> first = yum.addRpmAndRegenerate( file1 );
        final ScheduledTask<YumRepository> second = yum.addRpmAndRegenerate( file2 );
        futures.add( first );
        futures.add( second );

        waitFor( futures );
        // then
        assertThat( second, is( first ) );
        assertThat( ( (GenerateMetadataTask) first.getTask() ).getAddedFiles(), is( file1 + pathSeparator + file2 ) );

        final RepoMD repoMD = new RepoMD( new File( tmpDir, "repodata/repomd.xml" ) );

        final String content = IOUtils.toString(
            new GZIPInputStream( new FileInputStream( new File( tmpDir, repoMD.getPrimaryLocation() ) ) )
        );
        assertThat( content, containsString( RPM_NAME_1 ) );
        assertThat( content, containsString( RPM_NAME_2 ) );
    }

    private void waitFor( List<ScheduledTask<?>> futures )
        throws ExecutionException, InterruptedException
    {
        for ( ScheduledTask<?> future : futures )
        {
            future.get();
        }
    }

    private GenerateMetadataTask createYumRepositoryTask( final int repositoryId )
        throws Exception
    {
        final GenerateMetadataTask task = new GenerateMetadataTask(
            mock( EventBus.class ),
            mock( RepositoryRegistry.class ),
            yumRegistry,
            mock( RepositoryURLBuilder.class ),
            mock( RpmScanner.class ),
            nexusScheduler,
            mock( Manager.class )
        )
        {
            @Override
            protected YumRepository doRun()
                throws Exception
            {
                String threadName = Thread.currentThread().getName();
                LOG.debug( "Thread name : {}", threadName );
                if ( !threadNames.add( threadName ) )
                {
                    Assert.fail( "Uses the same thread : " + threadName );
                }
                Thread.sleep( 100 );
                return null;
            }
        };
        task.setRepositoryId( "REPO_" + repositoryId );
        return task;
    }

}
