package org.sonatype.maven.plugin.version;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.Text;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;

/**
 * Set the version of a POM and all of the places in the build where it's used as a parent. This is useful for setting a
 * sonatype-specific version for an internal release.
 * 
 * @goal set-version
 * @requiresDirectInvocation
 * @aggregator
 */
public class SetVersionMojo
    implements Mojo
{

    /**
     * @parameter default-value="${reactorProjects}"
     * @required
     * @readonly
     */
    private List projects;

    /**
     * @parameter expression="${artifactId}"
     * @required
     */
    private String artifactId;

    /**
     * @parameter expression="${newVersion}"
     * @required
     */
    private String newVersion;

    /**
     * @parameter expression="${extraPaths}"
     */
    private String extraPaths;

    private Log log;

    public void execute()
        throws MojoExecutionException
    {
        MavenProject project = getRootProject();

        updateVersion( project );

        for ( Iterator it = projects.iterator(); it.hasNext(); )
        {
            MavenProject p = (MavenProject) it.next();
            updateExtras( p );
        }
    }

    private void updateVersion( MavenProject project )
        throws MojoExecutionException
    {
        if ( project.getParent() != null && project.getVersion().equals( project.getParent().getVersion() ) )
        {
            updateParentVersion( project );
        }
        else
        {
            updateMainVersion( project );
        }

        if ( "pom".equals( project.getPackaging() ) )
        {
            for ( Iterator it = projects.iterator(); it.hasNext(); )
            {
                MavenProject child = (MavenProject) it.next();

                if ( project.equals( child.getParent() ) )
                {
                    updateVersion( child );
                }
            }
        }
    }

    private MavenProject getRootProject()
        throws MojoExecutionException
    {
        for ( Iterator it = projects.iterator(); it.hasNext(); )
        {
            MavenProject p = (MavenProject) it.next();
            if ( p.getArtifactId().equals( artifactId ) )
            {
                return p;
            }
        }

        throw new MojoExecutionException( "Could not find project with artifactId=" + artifactId );
    }

    private void updateExtras( MavenProject p )
        throws MojoExecutionException
    {
        if ( extraPaths != null )
        {
            String[] paths = extraPaths.split( "," );

            for ( int i = 0; i < paths.length; i++ )
            {
                String path = paths[i];

                getLog().info( "Updating version in xpath: " + path + " of POM: " + p.getFile() + " to: " + newVersion );
                doUpdateVersion( path, p, false );
            }
        }
    }

    private void updateParentVersion( MavenProject child )
        throws MojoExecutionException
    {
        getLog().info( "Updating parent version for: " + child.getId() + " to: " + newVersion );
        doUpdateVersion( "/p:project/p:parent/p:version", child, true );
    }

    private void updateMainVersion( MavenProject project )
        throws MojoExecutionException
    {
        getLog().info( "Updating project version for: " + project.getId() + " to: " + newVersion );
        doUpdateVersion( "/p:project/p:version", project, true );
    }

    private void doUpdateVersion( String xpath, MavenProject project, boolean required )
        throws MojoExecutionException
    {
        File pomFile = project.getFile();

        Document doc;
        try
        {
            doc = new SAXBuilder().build( pomFile );
        }
        catch ( JDOMException e )
        {
            throw new MojoExecutionException( "Failed to read POM: " + pomFile, e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to read POM: " + pomFile, e );
        }

        List elements;
        try
        {
            XPath xp = XPath.newInstance( xpath );

            if ( doc.getRootElement().getNamespace() == null )
            {
                doc.getRootElement().setNamespace(
                    Namespace
                        .getNamespace( "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd" ) );
            }

            xp.addNamespace( "p", doc.getRootElement().getNamespace().getURI() );
            xp.addNamespace( "", doc.getRootElement().getNamespace().getURI() );

            elements = xp.selectNodes( doc );
        }
        catch ( JDOMException e )
        {
            throw new MojoExecutionException( "Failed to resolve xpath: " + xpath + " in POM: " + pomFile, e );
        }

        if ( elements == null || elements.isEmpty() )
        {
            if ( !required )
            {
                getLog().info( "Nothing to do for POM: " + pomFile );
                return;
            }

            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( new XMLOutputter().outputString( doc ) );
            }

            String[] parts = xpath.split( "\\/" );
            Element e = doc.getRootElement();

            getLog().info( "Root element is: " + doc.getRootElement() );

            for ( int i = 2; i < parts.length && e != null; i++ )
            {
                List children = e.getChildren();
                if ( children != null )
                {
                    for ( Iterator it = children.iterator(); it.hasNext(); )
                    {
                        Element ec = (Element) it.next();
                        if ( ec.getName().equals( parts[i] ) )
                        {
                            e = ec;
                            break;
                        }
                    }
                }

                getLog().info( "Element for xpath part: " + parts[i] + " is: " + e );
            }

            if ( e == null )
            {
                getLog().info( "Cannot traverse manually to path: " + xpath );
            }
            else
            {
                getLog().info( "Manually traversed xpath: " + xpath + " to: " + new XMLOutputter().outputString( e ) );
            }

            System.out.flush();

            throw new MojoExecutionException( "Failed to resolve xpath: " + xpath + " in POM: " + pomFile );
        }

        for ( Iterator it = elements.iterator(); it.hasNext(); )
        {
            Element element = (Element) it.next();
            element.setContent( new Text( newVersion ) );
        }

        FileWriter out = null;
        try
        {
            pomFile.renameTo( new File( pomFile.getParentFile(), "pom.xml.bak" ) );
            out = new FileWriter( pomFile );
            new XMLOutputter().output( doc, out );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to write modified POM: " + pomFile, e );
        }
        finally
        {
            IOUtil.close( out );
        }
    }

    public Log getLog()
    {
        return log;
    }

    public void setLog( Log log )
    {
        this.log = log;
    }

}
