/**
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.yum.testsuite;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.sonatype.nexus.client.core.subsystem.content.Location.repositoryLocation;
import static org.sonatype.nexus.repository.yum.client.MetadataType.INDEX;
import static org.sonatype.nexus.repository.yum.client.MetadataType.PRIMARY_XML;

import org.junit.Test;
import org.sonatype.nexus.client.core.subsystem.repository.maven.MavenHostedRepository;

public class VersionedYumRepositoryIT
    extends YumRepositoryITSupport
{

    public VersionedYumRepositoryIT( final String nexusBundleCoordinates )
    {
        super( nexusBundleCoordinates );
    }

    @Test
    public void shouldGenerateVersionedRepoForVersion()
        throws Exception
    {
        final String repoName = givenRepositoryWithRpm();
        final String content = yum().getMetadata( repoName, "1.0", PRIMARY_XML, String.class );
        assertThat( content, containsString( "test-artifact" ) );
    }

    @Test
    public void shouldGenerateVersionedRepoForAlias()
        throws Exception
    {
        final String repoName = givenRepositoryWithRpm();
        yum().createOrUpdateAlias( repoName, "alias", "1.0" );
        final String content = yum().getMetadata( repoName, "alias", PRIMARY_XML, String.class );
        assertThat( content, containsString( "test-artifact" ) );
    }

    @Test
    public void shouldGenerateIndexHtml()
        throws Exception
    {
        final String repoName = givenRepositoryWithRpm();
        final String content = yum().getMetadata( repoName, "1.0", INDEX, String.class );
        assertThat( content, containsString( "<a href=\"repodata/\">repodata/</a>" ) );
    }

    private String givenRepositoryWithRpm()
        throws Exception
    {
        final MavenHostedRepository repository = repositories().create(
            MavenHostedRepository.class, repositoryIdForTest()
        ).excludeFromSearchResults().save();

        content().upload(
            repositoryLocation( repository.id(), "group/artifact/1.0/artifact-1.0.rpm" ),
            testData.resolveFile( "/rpms/test-artifact-1.2.3-1.noarch.rpm" )
        );
        sleep( 5, SECONDS );

        return repository.id();
    }
}
