package org.apache.maven.it;

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;

import javax.xml.parsers.*;

import junit.framework.*;

import org.apache.maven.it.util.cli.*;
import org.apache.maven.it.util.FileUtils;
import org.apache.maven.it.util.StringUtils;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

/**
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id$
 * @noinspection UseOfSystemOutOrSystemErr,RefusedBequest
 */
public class Verifier
{
    private static final String LOG_FILENAME = "log.txt";

    public String localRepo;

    private final String basedir;

    private final ByteArrayOutputStream outStream = new ByteArrayOutputStream();

    private final ByteArrayOutputStream errStream = new ByteArrayOutputStream();

    private final PrintStream originalOut;

    private final PrintStream originalErr;

    private List cliOptions = new ArrayList();

    private Properties systemProperties = new Properties();

    private Properties verifierProperties = new Properties();

    // TODO: needs to be configurable
    private static String localRepoLayout = "default";

    public Verifier( String basedir,
                     String settingsFile )
        throws VerificationException
    {
        this.basedir = basedir;

        originalOut = System.out;

        System.setOut( new PrintStream( outStream ) );

        originalErr = System.err;

        System.setErr( new PrintStream( errStream ) );

        findLocalRepo( settingsFile );
    }

    public Verifier( String basedir )
        throws VerificationException
    {
        this( basedir, null );
    }

    public void resetStreams()
    {
        System.setOut( originalOut );

        System.setErr( originalErr );
    }

    public void displayStreamBuffers()
    {
        String out = outStream.toString();

        if ( out != null && out.trim().length() > 0 )
        {
            System.out.println( "----- Standard Out -----" );

            System.out.println( out );
        }

        String err = errStream.toString();

        if ( err != null && err.trim().length() > 0 )
        {
            System.err.println( "----- Standard Error -----" );

            System.err.println( err );
        }
    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public void verify( boolean chokeOnErrorOutput )
        throws VerificationException
    {
        List lines = loadFile( basedir, "expected-results.txt", false );

        for ( Iterator i = lines.iterator(); i.hasNext(); )
        {
            String line = (String) i.next();

            verifyExpectedResult( line );
        }

        if ( chokeOnErrorOutput )
        {
            verifyErrorFreeLog();
        }
    }

    public void verifyErrorFreeLog()
        throws VerificationException
    {
        List lines;
        lines = loadFile( basedir, LOG_FILENAME, false );

        for ( Iterator i = lines.iterator(); i.hasNext(); )
        {
            String line = (String) i.next();

            // A hack to keep stupid velocity resource loader errors from triggering failure
            if ( line.indexOf( "[ERROR]" ) >= 0 && line.indexOf( "VM_global_library.vm" ) == -1 )
            {
                throw new VerificationException( "Error in execution." );
            }
        }
    }

    public Properties loadProperties( String filename )
        throws VerificationException
    {
        Properties properties = new Properties();

        FileInputStream fis;
        try
        {
            File propertiesFile = new File( basedir, filename );
            if ( propertiesFile.exists() )
            {
                fis = new FileInputStream( propertiesFile );
                properties.load( fis );
            }
        }
        catch ( FileNotFoundException e )
        {
            throw new VerificationException( "Error reading properties file", e );
        }
        catch ( IOException e )
        {
            throw new VerificationException( "Error reading properties file", e );
        }

        return properties;
    }

    public List loadFile( String basedir,
                          String filename,
                          boolean hasCommand )
        throws VerificationException
    {
        return loadFile( new File( basedir, filename ), hasCommand );
    }

    public List loadFile( File file,
                          boolean hasCommand )
        throws VerificationException
    {
        List lines = new ArrayList();

        if ( file.exists() )
        {
            try
            {
                BufferedReader reader = new BufferedReader( new FileReader( file ) );

                String line = reader.readLine();

                while ( line != null )
                {
                    line = line.trim();

                    if ( !line.startsWith( "#" ) && line.length() != 0 )
                    {
                        lines.addAll( replaceArtifacts( line, hasCommand ) );
                    }
                    line = reader.readLine();
                }

                reader.close();
            }
            catch ( FileNotFoundException e )
            {
                throw new VerificationException( e );
            }
            catch ( IOException e )
            {
                throw new VerificationException( e );
            }
        }

        return lines;
    }

    private List replaceArtifacts( String line,
                                   boolean hasCommand )
    {
        String MARKER = "${artifact:";
        int index = line.indexOf( MARKER );
        if ( index >= 0 )
        {
            String newLine = line.substring( 0, index );
            index = line.indexOf( "}", index );
            if ( index < 0 )
            {
                throw new IllegalArgumentException( "line does not contain ending artifact marker: '" + line + "'" );
            }
            String artifact = line.substring( newLine.length() + MARKER.length(), index );

            newLine += getArtifactPath( artifact );
            newLine += line.substring( index + 1 );

            List l = new ArrayList();
            l.add( newLine );

            int endIndex = newLine.lastIndexOf( '/' );

            String command = null;
            String filespec;
            if ( hasCommand )
            {
                int startIndex = newLine.indexOf( ' ' );

                command = newLine.substring( 0, startIndex );

                filespec = newLine.substring( startIndex + 1, endIndex );
            }
            else
            {
                filespec = newLine;
            }

            File dir = new File( filespec );
            addMetadataToList( dir, hasCommand, l, command );
            addMetadataToList( dir.getParentFile(), hasCommand, l, command );

            return l;
        }
        else
        {
            return Collections.singletonList( line );
        }
    }

    private static void addMetadataToList( File dir,
                                           boolean hasCommand,
                                           List l,
                                           String command )
    {
        if ( dir.exists() && dir.isDirectory() )
        {
            String[] files = dir.list( new FilenameFilter()
            {
                public boolean accept( File dir,
                                       String name )
                {
                    return name.startsWith( "maven-metadata" ) && name.endsWith( ".xml" );

                }
            } );

            for ( int i = 0; i < files.length; i++ )
            {
                if ( hasCommand )
                {
                    l.add( command + " " + new File( dir, files[i] ).getPath() );
                }
                else
                {
                    l.add( new File( dir, files[i] ).getPath() );
                }
            }
        }
    }

    private String getArtifactPath( String artifact )
    {
        StringTokenizer tok = new StringTokenizer( artifact, ":" );
        if ( tok.countTokens() != 4 )
        {
            throw new IllegalArgumentException( "Artifact must have 4 tokens: '" + artifact + "'" );
        }

        String[] a = new String[4];
        for ( int i = 0; i < 4; i++ )
        {
            a[i] = tok.nextToken();
        }

        String org = a[0];
        String name = a[1];
        String version = a[2];
        String ext = a[3];
        return getArtifactPath( org, name, version, ext );
    }

    private String getArtifactPath( String org,
                                    String name,
                                    String version,
                                    String ext )
    {
        if ( "maven-plugin".equals( ext ) )
        {
            ext = "jar";
        }
        String classifier = null;
        if ( "coreit-artifact".equals( ext ) )
        {
            ext = "jar";
            classifier = "it";
        }
        if ( "test-jar".equals( ext ) )
        {
            ext = "jar";
            classifier = "tests";
        }

        String repositoryPath;
        if ( "legacy".equals( localRepoLayout ) )
        {
            repositoryPath = org + "/" + ext + "s/" + name + "-" + version + "." + ext;
        }
        else if ( "default".equals( localRepoLayout ) )
        {
            repositoryPath = org.replace( '.', '/' );
            repositoryPath = repositoryPath + "/" + name + "/" + version;
            repositoryPath = repositoryPath + "/" + name + "-" + version;
            if ( classifier != null )
            {
                repositoryPath = repositoryPath + "-" + classifier;
            }
            repositoryPath = repositoryPath + "." + ext;
        }
        else
        {
            throw new IllegalStateException( "Unknown layout: " + localRepoLayout );
        }

        return localRepo + "/" + repositoryPath;
    }

    public List getArtifactFileNameList( String org,
                                         String name,
                                         String version,
                                         String ext )
    {
        List files = new ArrayList();
        String artifactPath = getArtifactPath( org, name, version, ext );
        File dir = new File( artifactPath );
        files.add( artifactPath );
        addMetadataToList( dir, false, files, null );
        addMetadataToList( dir.getParentFile(), false, files, null );
        return files;
    }

    public void executeHook( String filename )
        throws VerificationException
    {
        try
        {
            File f = new File( basedir, filename );

            if ( !f.exists() )
            {
                return;
            }

            List lines = loadFile( f, true );

            for ( Iterator i = lines.iterator(); i.hasNext(); )
            {
                String line = resolveCommandLineArg( (String) i.next() );

                executeCommand( line );
            }
        }
        catch ( VerificationException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new VerificationException( e );
        }
    }

    private void executeCommand( String line )
        throws VerificationException
    {
        int index = line.indexOf( " " );

        String cmd;

        String args = null;

        if ( index >= 0 )
        {
            cmd = line.substring( 0, index );

            args = line.substring( index + 1 );
        }
        else
        {
            cmd = line;
        }

        if ( "rm".equals( cmd ) )
        {
            System.out.println( "Removing file: " + args );

            File f = new File( args );

            if ( f.exists() && !f.delete() )
            {
                throw new VerificationException( "Error removing file - delete failed" );
            }
        }
        else if ( "rmdir".equals( cmd ) )
        {
            System.out.println( "Removing directory: " + args );

            try
            {
                File f = new File( args );

                FileUtils.deleteDirectory( f );
            }
            catch ( IOException e )
            {
                throw new VerificationException( "Error removing directory - delete failed" );
            }
        }
        else if ( "svn".equals( cmd ) )
        {
            launchSubversion( line, basedir );
        }
        else
        {
            throw new VerificationException( "unknown command: " + cmd );
        }
    }

    public static void launchSubversion( String line,
                                         String basedir )
        throws VerificationException
    {
        try
        {
            Commandline cli = new Commandline( line );                                   

            cli.setWorkingDirectory( basedir );

            Writer logWriter = new FileWriter( new File( basedir, LOG_FILENAME ) );

            StreamConsumer out = new WriterStreamConsumer( logWriter );

            StreamConsumer err = new WriterStreamConsumer( logWriter );

            System.out.println( "Command: " + Commandline.toString( cli.getCommandline() ) );

            int ret = CommandLineUtils.executeCommandLine( cli, out, err );

            logWriter.close();

            if ( ret > 0 )
            {
                System.err.println( "Exit code: " + ret );

                throw new VerificationException();
            }
        }
        catch ( CommandLineException e )
        {
            throw new VerificationException( e );
        }
        catch ( IOException e )
        {
            throw new VerificationException( e );
        }
    }

    private static String retrieveLocalRepo( String settingsXmlPath )
        throws VerificationException
    {
        UserModelReader userModelReader = new UserModelReader();

        String userHome = System.getProperty( "user.home" );

        File userXml;

        String repo = null;

        if ( settingsXmlPath != null )
        {
            System.out.println( "Using settings from " + settingsXmlPath );
            userXml = new File( settingsXmlPath );
        }
        else
        {
            userXml = new File( userHome, ".m2/settings.xml" );
        }

        if ( userXml.exists() )
        {
            userModelReader.parse( userXml );

            String localRepository = userModelReader.getLocalRepository();
            if ( localRepository != null )
            {
                repo = new File( localRepository ).getAbsolutePath();
            }
        }

        return repo;
    }

    public void deleteArtifact( String org,
                                String name,
                                String version,
                                String ext )
        throws IOException
    {
        List files = getArtifactFileNameList( org, name, version, ext );
        for ( Iterator i = files.iterator(); i.hasNext(); )
        {
            String fileName = (String) i.next();
            FileUtils.forceDelete( new File( fileName ) );
        }
    }

    public void assertFilePresent( String file )
    {
        try
        {
            verifyExpectedResult( file, true );
        }
        catch ( VerificationException e )
        {
            Assert.fail( e.getMessage() );
        }
    }

    public void assertFileNotPresent( String file )
    {
        try
        {
            verifyExpectedResult( file, false );
        }
        catch ( VerificationException e )
        {
            Assert.fail( e.getMessage() );
        }
    }

    private void verifyArtifactPresence( boolean wanted,
                                         String org,
                                         String name,
                                         String version,
                                         String ext )
    {
        List files = getArtifactFileNameList( org, name, version, ext );
        for ( Iterator i = files.iterator(); i.hasNext(); )
        {
            String fileName = (String) i.next();
            try
            {
                verifyExpectedResult( fileName, wanted );
            }
            catch ( VerificationException e )
            {
                Assert.fail( e.getMessage() );
            }
        }
    }

    public void assertArtifactPresent( String org,
                                       String name,
                                       String version,
                                       String ext )
    {
        verifyArtifactPresence( true, org, name, version, ext );
    }

    public void assertArtifactNotPresent( String org,
                                          String name,
                                          String version,
                                          String ext )
    {
        verifyArtifactPresence( false, org, name, version, ext );
    }

    private void verifyExpectedResult( String line )
        throws VerificationException
    {
        boolean wanted = true;
        if ( line.startsWith( "!" ) )
        {
            line = line.substring( 1 );
            wanted = false;
        }

        verifyExpectedResult( line, wanted );
    }

    private void verifyExpectedResult( String line,
                                       boolean wanted )
        throws VerificationException
    {
        if ( line.indexOf( "!/" ) > 0 )
        {
            String urlString = "jar:file:" + basedir + "/" + line;

            InputStream is = null;
            try
            {
                URL url = new URL( urlString );

                is = url.openStream();

                if ( is == null )
                {
                    if ( wanted )
                    {
                        throw new VerificationException( "Expected JAR resource was not found: " + line );
                    }
                }
                else
                {
                    if ( !wanted )
                    {
                        throw new VerificationException( "Unwanted JAR resource was found: " + line );
                    }
                }
            }
            catch ( MalformedURLException e )
            {
                throw new VerificationException( "Error looking for JAR resource", e );
            }
            catch ( IOException e )
            {
                throw new VerificationException( "Error looking for JAR resource", e );
            }
            finally
            {
                if ( is != null )
                {
                    try
                    {
                        is.close();
                    }
                    catch ( IOException e )
                    {
                        System.err.println( "WARN: error closing stream: " + e );
                    }
                }
            }
        }
        else
        {
            File expectedFile = new File( line );

            if ( !expectedFile.isAbsolute() && !line.startsWith( "/" ) )
            {
                expectedFile = new File( basedir, line );
            }

            if ( line.indexOf( '*' ) > -1 )
            {
                File parent = expectedFile.getParentFile();

                if ( !parent.exists() )
                {
                    if ( wanted )
                    {
                        throw new VerificationException( "Expected file was not found: " + expectedFile.getPath() );
                    }
                }
                else
                {
                    String shortNamePattern = expectedFile.getName().replaceAll( "\\*", ".*" );

                    String[] candidates = parent.list();

                    boolean found = false;

                    if ( candidates != null )
                    {
                        for ( int i = 0; i < candidates.length; i++ )
                        {
                            if ( candidates[i].matches( shortNamePattern ) )
                            {
                                found = true;
                                break;
                            }
                        }
                    }

                    if ( !found && wanted )
                    {
                        throw new VerificationException(
                            "Expected file pattern was not found: " + expectedFile.getPath() );
                    }
                    else if ( found && !wanted )
                    {
                        throw new VerificationException( "Unwanted file pattern was found: " + expectedFile.getPath() );
                    }
                }
            }
            else
            {
                if ( !expectedFile.exists() )
                {
                    if ( wanted )
                    {
                        throw new VerificationException( "Expected file was not found: " + expectedFile.getPath() );
                    }
                }
                else
                {
                    if ( !wanted )
                    {
                        throw new VerificationException( "Unwanted file was found: " + expectedFile.getPath() );
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public void executeGoal( String goal )
        throws VerificationException
    {
        executeGoals( Arrays.asList( new String[]{goal} ) );
    }

    public void executeGoals( List goals )
        throws VerificationException
    {
        String mavenHome = System.getProperty( "maven.home" );

        if ( goals.size() == 0 )
        {
            throw new VerificationException( "No goals specified" );
        }

        List allGoals = new ArrayList();

        allGoals.add( "clean:clean" );

        allGoals.addAll( goals );

        int ret;

        try
        {
            Commandline cli = new Commandline();

            cli.setWorkingDirectory( basedir );

            String executable;

            // Use a strategy for finding the maven executable, John has a simple method like this
            // but a little strategy + chain of command would be nicer.

            if ( mavenHome != null )
            {
                executable = mavenHome + "/bin/mvn";
            }
            else
            {
                File f = new File( System.getProperty( "user.home" ), "m2/bin/mvn" );

                if ( f.exists() )
                {
                    executable = f.getAbsolutePath();
                }
                else
                {
                    executable = "mvn";
                }
            }

            cli.setExecutable( executable );

            for ( Iterator it = cliOptions.iterator(); it.hasNext(); )
            {
                String key = (String) it.next();

                String resolvedArg = resolveCommandLineArg( key );

                cli.createArgument().setLine( resolvedArg );
            }

            cli.createArgument().setValue( "-e" );

            cli.createArgument().setValue( "--no-plugin-registry" );

            cli.createArgument().setValue( "--batch-mode" );

            for ( Iterator i = systemProperties.keySet().iterator(); i.hasNext(); )
            {
                String key = (String) i.next();
                cli.createArgument().setLine( "-D" + key + "=" + systemProperties.getProperty( key ) );
            }

            boolean useMavenRepoLocal =
                Boolean.valueOf( verifierProperties.getProperty( "use.mavenRepoLocal", "true" ) ).booleanValue();

            if ( useMavenRepoLocal )
            {
                // Note: Make sure that the repo is surrounded by quotes as it can possibly have
                // spaces in its path.
                cli.createArgument().setLine( "-Dmaven.repo.local=" + "\"" + localRepo + "\"" );
            }

            for ( Iterator i = allGoals.iterator(); i.hasNext(); )
            {
                cli.createArgument().setValue( (String) i.next() );
            }

            Writer logWriter = new FileWriter( new File( basedir, LOG_FILENAME ) );

            StreamConsumer out = new WriterStreamConsumer( logWriter );

            StreamConsumer err = new WriterStreamConsumer( logWriter );

            System.out.println( "Command: " + Commandline.toString( cli.getCommandline() ) );

            ret = CommandLineUtils.executeCommandLine( cli, out, err );

            logWriter.close();
        }
        catch ( CommandLineException e )
        {
            throw new VerificationException( e );
        }
        catch ( IOException e )
        {
            throw new VerificationException( e );
        }

        if ( ret > 0 )
        {
            System.err.println( "Exit code: " + ret );

            throw new VerificationException( "Exit code was non-zero: " + ret );
        }
    }

    private String resolveCommandLineArg( String key )
    {
        String result = key.replaceAll( "\\$\\{basedir\\}", basedir );
        if ( result.indexOf( "\\\\" ) >= 0 )
        {
            result = result.replaceAll( "\\\\", "\\" );
        }
        result = result.replaceAll( "\\/\\/", "\\/" );

        return result;
    }

    private static List discoverIntegrationTests( String directory )
        throws VerificationException
    {
        try
        {
            ArrayList tests = new ArrayList();

            List subTests = FileUtils.getFiles( new File( directory ), "**/goals.txt", null );

            for ( Iterator i = subTests.iterator(); i.hasNext(); )
            {
                File testCase = (File) i.next();
                tests.add( testCase.getParent() );
            }

            return tests;
        }
        catch ( IOException e )
        {
            throw new VerificationException( directory + " is not a valid test case container", e );
        }
    }

    private void displayLogFile()
    {
        System.out.println( "Log file contents:" );
        try
        {
            BufferedReader reader = new BufferedReader( new FileReader( new File( basedir, LOG_FILENAME ) ) );
            String line = reader.readLine();
            while ( line != null )
            {
                System.out.println( line );
                line = reader.readLine();
            }
            reader.close();
        }
        catch ( FileNotFoundException e )
        {
            System.err.println( "Error: " + e );
        }
        catch ( IOException e )
        {
            System.err.println( "Error: " + e );
        }
    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public static void main( String args[] )
        throws VerificationException
    {
        String basedir = System.getProperty( "user.dir" );

        List tests = null;

        List argsList = new ArrayList();

        String settingsFile = null;

        // skip options
        for ( int i = 0; i < args.length; i++ )
        {
            if ( args[i].startsWith( "-D" ) )
            {
                int index = args[i].indexOf( "=" );
                if ( index >= 0 )
                {
                    System.setProperty( args[i].substring( 2, index ), args[i].substring( index + 1 ) );
                }
                else
                {
                    System.setProperty( args[i].substring( 2 ), "true" );
                }
            }
            else if ( "-s".equals( args[i] ) || "--settings".equals( args[i] ) )
            {
                if ( i == args.length - 1 )
                {
                    // should have been detected before
                    throw new IllegalStateException( "missing argument to -s" );
                }
                i += 1;

                settingsFile = args[i];
            }
            else if ( args[i].startsWith( "-" ) )
            {
                System.out.println( "skipping unrecognised argument: " + args[i] );
            }
            else
            {
                argsList.add( args[i] );
            }
        }

        if ( argsList.size() == 0 )
        {
            if ( FileUtils.fileExists( basedir + File.separator + "integration-tests.txt" ) )
            {
                try
                {
                    tests = FileUtils.loadFile( new File( basedir, "integration-tests.txt" ) );
                }
                catch ( IOException e )
                {
                    System.err.println( "Unable to load integration tests file" );

                    System.err.println( e.getMessage() );

                    System.exit( 2 );
                }
            }
            else
            {
                tests = discoverIntegrationTests( "." );
            }
        }
        else
        {
            tests = new ArrayList( argsList.size() );
            NumberFormat fmt = new DecimalFormat( "0000" );
            for ( int i = 0; i < argsList.size(); i++ )
            {
                String test = (String) argsList.get( i );
                if ( test.endsWith( "," ) )
                {
                    test = test.substring( 0, test.length() - 1 );
                }

                if ( StringUtils.isNumeric( test ) )
                {

                    test = "it" + fmt.format( Integer.valueOf( test ) );
                    test.trim();
                    tests.add( test );
                }
                else if ( "it".startsWith( test ) )
                {
                    test = test.trim();
                    if ( test.length() > 0 )
                    {
                        tests.add( test );
                    }
                }
                else if ( FileUtils.fileExists( test ) && new File( test ).isDirectory() )
                {
                    tests.addAll( discoverIntegrationTests( test ) );
                }
                else
                {
                    System.err.println(
                        "[WARNING] rejecting " + test + " as an invalid test or test source directory" );
                }
            }
        }

        if ( tests.size() == 0 )
        {
            System.out.println( "No tests to run" );
        }

        int exitCode = 0;

        List failed = new ArrayList();
        for ( Iterator i = tests.iterator(); i.hasNext(); )
        {
            String test = (String) i.next();

            System.out.print( test + "... " );

            String dir = basedir + "/" + test;

            if ( !new File( dir, "goals.txt" ).exists() )
            {
                System.err.println( "Test " + test + " in " + dir + " does not exist" );

                System.exit( 2 );
            }

            Verifier verifier = new Verifier( dir );
            verifier.findLocalRepo( settingsFile );

            System.out.println( "Using default local repository: " + verifier.localRepo );


            try
            {
                runIntegrationTest( verifier );
            }
            catch ( Throwable e )
            {
                verifier.resetStreams();

                System.out.println( "FAILED" );

                verifier.displayStreamBuffers();

                System.out.println( ">>>>>> Error Stacktrace:" );
                e.printStackTrace( System.out );
                System.out.println( "<<<<<< Error Stacktrace" );

                verifier.displayLogFile();

                exitCode = 1;

                failed.add( test );
            }
        }

        System.out.println( tests.size() - failed.size() + "/" + tests.size() + " passed" );
        if ( !failed.isEmpty() )
        {
            System.out.println( "Failed tests: " + failed );
        }

        System.exit( exitCode );
    }

    private void findLocalRepo( String settingsFile )
        throws VerificationException
    {
        if ( localRepo == null )
        {
            localRepo = System.getProperty( "maven.repo.local" );
        }

        if ( localRepo == null )
        {
            localRepo = retrieveLocalRepo( settingsFile );
        }

        if ( localRepo == null )
        {
            localRepo = System.getProperty( "user.home" ) + "/.m2/repository";
        }

        File repoDir = new File( localRepo );
        if ( !repoDir.exists() )
        {
            repoDir.mkdirs();
        }
    }

    private static void runIntegrationTest( Verifier verifier )
        throws VerificationException
    {
        verifier.executeHook( "prebuild-hook.txt" );

        Properties properties = verifier.loadProperties( "system.properties" );

        Properties controlProperties = verifier.loadProperties( "verifier.properties" );

        boolean chokeOnErrorOutput =
            Boolean.valueOf( controlProperties.getProperty( "failOnErrorOutput", "true" ) ).booleanValue();

        List goals = verifier.loadFile( verifier.basedir, "goals.txt", false );

        List cliOptions = verifier.loadFile( verifier.basedir, "cli-options.txt", false );

        verifier.setCliOptions( cliOptions );

        verifier.setSystemProperties( properties );

        verifier.setVerifierProperties( controlProperties );

        verifier.executeGoals( goals );

        verifier.executeHook( "postbuild-hook.txt" );

        System.out.println( "*** Verifying: fail when [ERROR] detected? " + chokeOnErrorOutput + " ***" );

        verifier.verify( chokeOnErrorOutput );

        verifier.resetStreams();

        System.out.println( "OK" );
    }

    static class UserModelReader
        extends DefaultHandler
    {
        private String localRepository;

        private StringBuffer currentBody = new StringBuffer();

        public void parse( File file )
            throws VerificationException
        {
            try
            {
                SAXParserFactory saxFactory = SAXParserFactory.newInstance();

                SAXParser parser = saxFactory.newSAXParser();

                InputSource is = new InputSource( new FileInputStream( file ) );

                parser.parse( is, this );
            }
            catch ( FileNotFoundException e )
            {
                throw new VerificationException( e );
            }
            catch ( IOException e )
            {
                throw new VerificationException( e );
            }
            catch ( ParserConfigurationException e )
            {
                throw new VerificationException( e );
            }
            catch ( SAXException e )
            {
                throw new VerificationException( e );
            }
        }

        public void warning( SAXParseException spe )
        {
            printParseError( "Warning", spe );
        }

        public void error( SAXParseException spe )
        {
            printParseError( "Error", spe );
        }

        public void fatalError( SAXParseException spe )
        {
            printParseError( "Fatal Error", spe );
        }

        private final void printParseError( String type,
                                            SAXParseException spe )
        {
            System.err.println(
                type + " [line " + spe.getLineNumber() + ", row " + spe.getColumnNumber() + "]: " + spe.getMessage() );
        }

        public String getLocalRepository()
        {
            return localRepository;
        }

        public void characters( char[] ch,
                                int start,
                                int length )
            throws SAXException
        {
            currentBody.append( ch, start, length );
        }

        public void endElement( String uri,
                                String localName,
                                String rawName )
            throws SAXException
        {
            if ( "localRepository".equals( rawName ) )
            {
                if ( notEmpty( currentBody.toString() ) )
                {
                    localRepository = currentBody.toString().trim();
                }
                else
                {
                    throw new SAXException(
                        "Invalid mavenProfile entry. Missing one or more " + "fields: {localRepository}." );
                }
            }

            currentBody = new StringBuffer();
        }

        private boolean notEmpty( String test )
        {
            return test != null && test.trim().length() > 0;
        }

        public void reset()
        {
            this.currentBody = null;
            this.localRepository = null;
        }
    }

    public List getCliOptions()
    {
        return cliOptions;
    }

    public void setCliOptions( List cliOptions )
    {
        this.cliOptions = cliOptions;
    }

    public Properties getSystemProperties()
    {
        return systemProperties;
    }

    public void setSystemProperties( Properties systemProperties )
    {
        this.systemProperties = systemProperties;
    }

    public Properties getVerifierProperties()
    {
        return verifierProperties;
    }

    public void setVerifierProperties( Properties verifierProperties )
    {
        this.verifierProperties = verifierProperties;
    }

}

