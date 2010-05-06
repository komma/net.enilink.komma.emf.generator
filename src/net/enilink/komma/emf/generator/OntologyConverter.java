/*
 * Copyright (c) 2008, 2010, James Leigh All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution. 
 * - Neither the name of the openrdf.org nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */
package net.enilink.komma.emf.generator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.openrdf.model.Namespace;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.result.Result;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.rdfxml.RDFXMLWriter;
import org.openrdf.sail.memory.MemoryStore;
import org.openrdf.store.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.vocab.owl.Ontology;
import net.enilink.komma.common.AbstractKommaPlugin;
import net.enilink.komma.core.IKommaManager;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;
import net.enilink.komma.sesame.DecoratingSesameManagerFactory;
import net.enilink.komma.sesame.ISesameManager;

/**
 * A Facade to CodeGenerator and OwlGenerator classes. This class provides a
 * simpler interface to create concept packages and build ontologies. Unlike the
 * composed classes, this class reads and creates jar packages.
 * 
 * @author James Leigh
 * 
 */
public class OntologyConverter {
	private static final Options options = new Options();
	static {
		Option pkg = new Option("b", "bind", true,
				"Binds the package name and namespace together");
		pkg.setArgName("package=uri");
		Option jar = new Option("d", "dir", true,
				"directory where the ecore files will be saved");
		jar.setArgName("jar file");
		Option file = new Option("r", "rdf", true,
				"filename where the rdf ontology will be saved");
		file.setArgName("RDF file");
		Option prefix = new Option("p", "prefix", true,
				"prefix the property names with namespace prefix");
		prefix.setArgName("prefix");
		Option baseClass = new Option("e", "extends", true,
				"super class that all concepts should extend");
		baseClass.setArgName("full class name");
		options.addOption(baseClass);
		options.addOption(prefix);
		options.addOption("h", "help", false, "print this message");
		options.addOption(pkg);
		options.addOption(jar);
		options.addOption(file);
	}

	public static void main(String[] args) throws Exception {
		try {
			CommandLine line = new GnuParser().parse(options, args);
			if (line.hasOption('h')) {
				HelpFormatter formatter = new HelpFormatter();
				String cmdLineSyntax = "codegen [options] [ontology | jar]...";
				String header = "[ontology | jar]... are a list of RDF and jar files that should be imported before converting.";
				formatter.printHelp(cmdLineSyntax, header, options, "");
				return;
			}
			if (!line.hasOption('b'))
				throw new ParseException("Required bind option missing");
			if (!line.hasOption('d') && !line.hasOption('r'))
				throw new ParseException("Required jar or rdf option missing");
			if (line.hasOption('d') && line.hasOption('r'))
				throw new ParseException(
						"Only one directory or rdf option can be present");
			OntologyConverter converter = new OntologyConverter();
			String prefix = line.getOptionValue('p');
			converter.setPropertyNamesPrefix(prefix);
			if (line.hasOption('e')) {
				converter.setBaseClasses(line.getOptionValues('e'));
			}
			findJars(line.getArgs(), 0, converter);
			findECores(line.getArgs(), 0, converter);
			findRdfSources(line.getArgs(), 0, converter);
			for (String value : line.getOptionValues('b')) {
				String[] split = value.split("=", 2);
				if (split.length != 2) {
					throw new ParseException("Invalid bind option: " + value);
				}
				converter.bindPackageToNamespace(split[0], split[1]);
			}
			converter.init();
			if (line.hasOption('d')) {
				converter.createECore(new File(line.getOptionValue('d')));
			} else {
				converter.createOntology(new File(line.getOptionValue('r')));
			}
			return;
		} catch (ParseException exp) {
			System.err.println(exp.getMessage());
			System.exit(1);
		}
	}

	private static void findRdfSources(String[] args, int offset,
			OntologyConverter converter) throws MalformedURLException {
		for (int i = offset; i < args.length; i++) {
			URL url;
			File file = new File(args[i]);
			if (file.isDirectory() || args[i].endsWith(".jar")
					|| args[i].endsWith(".ecore"))
				continue;
			if (file.exists()) {
				url = file.toURI().toURL();
			} else {
				url = new URL(args[i]);
			}
			converter.addRdfSource(url);
		}
	}

	private static void findECores(String[] args, int offset,
			OntologyConverter converter) throws MalformedURLException {
		for (int i = offset; i < args.length; i++) {
			URL url;
			File file = new File(args[i]);
			if (file.exists()) {
				url = file.toURI().toURL();
			} else {
				url = new URL(args[i]);
			}
			if (file.isDirectory() || args[i].endsWith(".ecore")) {
				converter.addEcore(url);
			}
		}
	}

	private static void findJars(String[] args, int offset,
			OntologyConverter converter) throws MalformedURLException {
		for (int i = offset; i < args.length; i++) {
			URL url;
			File file = new File(args[i]);
			if (file.exists()) {
				url = file.toURI().toURL();
			} else {
				url = new URL(args[i]);
			}
			if (file.isDirectory() || args[i].endsWith(".jar")) {
				converter.addJar(url);
			}
		}
	}

	final Logger logger = LoggerFactory.getLogger(OntologyConverter.class);

	private boolean importJarOntologies = true;

	private List<URL> jars = new ArrayList<URL>();

	private List<URL> ecores = new ArrayList<URL>();

	private List<URL> rdfSources = new ArrayList<URL>();

	private Map<String, String> namespaces = new HashMap<String, String>();

	/** namespace -&gt; package */
	private Map<String, String> packages = new HashMap<String, String>();

	private Repository repository;

	private URLClassLoader cl;

	private String propertyNamesPrefix;

	private String[] baseClasses;

	/**
	 * If the ontologies bundled with the included jars should be imported.
	 * 
	 * @return <code>true</code> if the ontology will be imported.
	 */
	public boolean isImportJarOntologies() {
		return importJarOntologies;
	}

	/**
	 * If the ontologies bundled with the included jars should be imported.
	 * 
	 * @param importJarOntologies
	 *            <code>true</code> if the ontology will be imported.
	 */
	public void setImportJarOntologies(boolean importJarOntologies) {
		this.importJarOntologies = importJarOntologies;
	}

	/**
	 * The property names prefix or null for default prefix.
	 */
	public String getPropertyNamesPrefix() {
		return propertyNamesPrefix;
	}

	/**
	 * The property names prefix or null for default prefix.
	 */
	public void setPropertyNamesPrefix(String propertyNamesPrefix) {
		this.propertyNamesPrefix = propertyNamesPrefix;
	}

	/**
	 * Array of Java Class names that all concepts will extend.
	 * 
	 * @return Array of Java Class names that all concepts will extend.
	 */
	public String[] getBaseClasses() {
		return baseClasses;
	}

	/**
	 * Array of Java Class names that all concepts will extend.
	 * 
	 * @param strings
	 */
	public void setBaseClasses(String[] strings) {
		this.baseClasses = strings;
	}

	/**
	 * Add a jar of classes to include in the class-path.
	 * 
	 * @param url
	 */
	public void addJar(URL url) {
		jars.add(url);
	}

	/**
	 * Add an ECore.
	 * 
	 * @param url
	 */
	public void addEcore(URL url) {
		ecores.add(url);
	}

	/**
	 * Adds an RDF file to the local repository.
	 * 
	 * @param url
	 */
	public void addRdfSource(URL url) {
		rdfSources.add(url);
	}

	/**
	 * Set the prefix that should be used for this ontology namespace.
	 * 
	 * @param prefix
	 * @param namespace
	 */
	public void setNamespace(String prefix, String namespace) {
		namespaces.put(prefix, namespace);
	}

	/**
	 * Binds this namespace with the package name.
	 * 
	 * @param pkgName
	 * @param namespace
	 */
	public void bindPackageToNamespace(String pkgName, String namespace) {
		packages.put(namespace, pkgName);
	}

	/**
	 * Create the local repository and load the RDF files.
	 * 
	 * @throws Exception
	 */
	public void init() throws Exception {
		cl = createClassLoader(jars);
		Thread.currentThread().setContextClassLoader(cl);
		repository = createRepository(cl);
		for (URL url : rdfSources) {
			loadOntology(repository, url);
		}
	}

	/**
	 * Generate an OWL ontology from the JavaBeans in the included jars.
	 * 
	 * @param rdfOutputFile
	 * @throws Exception
	 * @see {@link #addOntology(URI, String)}
	 * @see {@link #addEcore(URL)}
	 */
	public void createOntology(File rdfOutputFile) throws Exception {
		DecoratingSesameManagerFactory factory = new DecoratingSesameManagerFactory(
				new KommaModule(), repository);
		final ISesameManager manager = factory.createKommaManager();
		createOntology(manager, rdfOutputFile);
	}

	/**
	 * Generate Elmo concept Java classes from the ontology in the local
	 * repository.
	 * 
	 * @param jarOutputFile
	 * @throws Exception
	 * @see {@link #addOntology(URI, String)}
	 * @see {@link #addRdfSource(URL)}
	 */
	public void createECore(File directory) throws Exception {
		createECorePackages(repository, cl, directory);
	}

	protected Repository createRepository() throws StoreException {
		Repository repository = new SailRepository(new MemoryStore());
		repository.initialize();
		return repository;
	}

	private URLClassLoader createClassLoader(List<URL> importJars)
			throws MalformedURLException {
		Thread thread = Thread.currentThread();
		ClassLoader cl = thread.getContextClassLoader();
		String name = OntologyConverter.class.getName().replace('.', '/');
		if (cl == null || cl.getResource(name + ".class") == null) {
			cl = OntologyConverter.class.getClassLoader();
		}
		URL[] classpath = importJars.toArray(new URL[0]);
		if (cl instanceof URLClassLoader) {
			URL[] urls = ((URLClassLoader) cl).getURLs();
			URL[] jars = classpath;
			classpath = new URL[jars.length + urls.length];
			System.arraycopy(jars, 0, classpath, 0, jars.length);
			System.arraycopy(urls, 0, classpath, jars.length, urls.length);
		}
		return URLClassLoader.newInstance(classpath, cl);
	}

	private Repository createRepository(ClassLoader cl) throws StoreException,
			IOException, RDFParseException {
		Repository repository = createRepository();
		RepositoryConnection conn = repository.getConnection();
		try {
			for (Map.Entry<String, String> e : namespaces.entrySet()) {
				conn.setNamespace(e.getKey(), e.getValue());
			}
		} finally {
			conn.close();
		}
		if (importJarOntologies) {
			for (String owl : loadOntologyList(cl)) {
				URL url = cl.getResource(owl);
				loadOntology(repository, url);
			}
		}
		return repository;
	}

	@SuppressWarnings("unchecked")
	private Collection<String> loadOntologyList(ClassLoader cl)
			throws IOException {
		Properties ontologies = new Properties();
		String name = "META-INF/org.openrdf.elmo.ontologies";
		Enumeration<URL> resources = cl.getResources(name);
		while (resources.hasMoreElements()) {
			URL url = resources.nextElement();
			ontologies.load(url.openStream());
		}
		Collection<?> list = ontologies.keySet();
		return (Collection<String>) list;
	}

	private void loadOntology(Repository repository, URL url)
			throws StoreException, IOException, RDFParseException {
		String filename = url.toString();
		RDFFormat format = formatForFileName(filename);
		RepositoryConnection conn = repository.getConnection();
		ValueFactory vf = conn.getValueFactory();
		try {
			conn.add(url, "", format, vf.createURI(url.toExternalForm()));
		} finally {
			conn.close();
		}
	}

	private RDFFormat formatForFileName(String filename) {
		RDFFormat format = RDFFormat.forFileName(filename);
		if (format != null)
			return format;
		if (filename.endsWith(".owl"))
			return RDFFormat.RDFXML;
		throw new IllegalArgumentException("Unknow RDF format for " + filename);
	}

	private void createOntology(IKommaManager manager, File output)
			throws Exception {
		if (!AbstractKommaPlugin.IS_ECLIPSE_RUNNING) {
			URL ecoreUrl = getClass().getClassLoader().getResource(
					"model/Ecore.ecore");
			URIConverter.INSTANCE.getURIMap().put(
					org.eclipse.emf.common.util.URI
							.createURI(EcorePackage.eNS_URI),
					org.eclipse.emf.common.util.URI.createURI(ecoreUrl.toURI()
							.toString()));

			EcorePackage.eINSTANCE.getEPackage();

			EPackage.Registry.INSTANCE.put(
					"platform:/plugin/org.eclipse.emf.ecore/model/Ecore.ecore",
					EcorePackage.eINSTANCE);
		}

		for (String namespace : packages.keySet()) {
			URI ontologyUri = URIImpl.createURI(namespace);

			manager.createNamed(ontologyUri, Ontology.class);
		}

		for (URL ecore : ecores) {
			new Ecore2OWLTransformer(manager)
					.ecore2OWL(new File(ecore.toURI()).getAbsolutePath(),
							Collections.<String, String> emptyMap());
		}

		if (output.getParentFile() != null) {
			output.getParentFile().mkdirs();
		}

		Writer out = new FileWriter(output);
		RDFXMLWriter writer = new RDFXMLWriter(out);
		try {
			writer.startRDF();
			try {

				RepositoryConnection conn = repository.getConnection();

				for (Namespace namespace : conn.getNamespaces().asList()) {
					writer.handleNamespace(namespace.getPrefix(), namespace
							.getName());
				}

				Result<Statement> stmts = conn.match(
						(org.openrdf.model.Resource) null, null, null, false,
						(org.openrdf.model.Resource) null);
				while (stmts.hasNext()) {
					writer.handleStatement(stmts.next());
				}

				conn.close();
			} catch (StoreException e) {
				e.printStackTrace();
			}

			// writer.printOntology(ontModel.getOntologyURI());
			writer.endRDF();
		} catch (RDFHandlerException e) {
			e.printStackTrace();
		}
	}

	private void createECorePackages(Repository repository, URLClassLoader cl,
			File output) throws Exception {
		Map<String, EPackage> ePackages = new HashMap<String, EPackage>();
		OWL2EcoreTransformer transformer = new OWL2EcoreTransformer(ePackages,
				packages);

		generateEcore(repository, cl, transformer);
		if (ePackages.isEmpty())
			throw new IllegalArgumentException(
					"No classes found - Try a different namespace.");

		saveEcore(output, ePackages);
	}

	private void generateEcore(Repository repository, ClassLoader cl,
			OWL2EcoreTransformer transformer) throws Exception {
		EcoreGenerator gen = new EcoreGenerator();
		gen.setPropertyNamesPrefix(propertyNamesPrefix);
		if (baseClasses != null) {
			List<Class<?>> base = new ArrayList<Class<?>>();
			for (String bc : baseClasses) {
				base.add(Class.forName(bc, true, cl));
			}
			gen.setBaseClasses(base.toArray(new Class<?>[base.size()]));
		}
		gen.setRepository(repository);

		for (Map.Entry<String, String> e : packages.entrySet()) {
			gen.bindPackageToNamespace(e.getValue(), e.getKey());
		}
		gen.init();
		gen.exportECore(transformer);
	}

	private void saveEcore(File output, Map<String, EPackage> ePackages)
			throws IOException {
		// save ecore model
		ResourceSet resourceSet = new ResourceSetImpl();
		resourceSet
				.getResourceFactoryRegistry()
				.getExtensionToFactoryMap()
				.put(
						org.eclipse.emf.ecore.resource.Resource.Factory.Registry.DEFAULT_EXTENSION,
						new EcoreResourceFactoryImpl());

		List<Resource> resources = new ArrayList<Resource>();
		for (Map.Entry<String, EPackage> entry : ePackages.entrySet()) {
			String packageName = packages.get(entry.getKey());
			// if (packageName == null || packageName.trim().length() == 0) {
			// System.out.println("no file for package: " + entry.getKey());
			// continue;
			// }

			File outputEcoreFile = new File(output, packageName + ".ecore");
			File dir = outputEcoreFile.getParentFile();
			if (!dir.exists()) {
				dir.mkdirs();
			}

			org.eclipse.emf.ecore.resource.Resource resource = resourceSet
					.createResource(org.eclipse.emf.common.util.URI
							.createFileURI(outputEcoreFile.getAbsolutePath()));
			resource.getContents().add(entry.getValue());
			resources.add(resource);
		}

		for (Resource resource : resources) {
			System.out.println("saving: " + resource.getURI());
			resource.save(Collections.EMPTY_MAP);
		}
	}
}
