/*
 * Copyright (c) 2007, 2010, James Leigh All rights reserved.
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

import java.util.HashMap;
import java.util.Map;

import org.openrdf.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.komma.generator.OwlNormalizer;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IKommaManager;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.sesame.DecoratingSesameManagerFactory;
import net.enilink.komma.sesame.ISesameManager;

/**
 * Converts OWL ontologies into JavaBeans. This class can be used to create Elmo
 * concepts or other JavaBean interfaces or classes.
 * 
 * @author James Leigh
 * 
 */
public class EcoreGenerator {
	private static final String SELECT_CLASSES = "PREFIX rdfs: <"
			+ RDFS.NAMESPACE
			+ "> PREFIX owl: <"
			+ OWL.NAMESPACE
			+ "> SELECT DISTINCT ?bean WHERE { { ?bean a owl:Class } UNION {?bean a rdfs:Datatype } }";

	final Logger logger = LoggerFactory.getLogger(EcoreGenerator.class);

	private DecoratingSesameManagerFactory factory;

	/** namespace -&gt; package */
	private Map<String, String> packages = new HashMap<String, String>();

	private String propertyNamesPrefix;

	private Class<?>[] baseClasses = new Class<?>[0];

	private Exception exception;

	public Class<?>[] getBaseClasses() {
		return baseClasses;
	}

	public void setBaseClasses(Class<?>[] baseClasses) {
		this.baseClasses = baseClasses;
	}

	public String getPropertyNamesPrefix() {
		return propertyNamesPrefix;
	}

	public void setPropertyNamesPrefix(String prefixPropertyNames) {
		this.propertyNamesPrefix = prefixPropertyNames;
	}

	public void bindPackageToNamespace(String pkgName, String namespace) {
		packages.put(namespace, pkgName);
	}

	public void setRepository(Repository repository) {
		this.factory = new DecoratingSesameManagerFactory(new KommaModule(),
				repository);
	}

	public void init() throws Exception {
		OwlNormalizer normalizer = new OwlNormalizer();
		final ISesameManager manager = factory.createKommaManager();
		normalizer.setSesameManager(manager);
		normalizer.normalize();
		manager.close();
	}

	public void exportECore(final OWL2EcoreTransformer transformer)
			throws Exception {
		final IKommaManager manager = factory.createKommaManager();
		try {
			IQuery<IEntity> query = manager.createQuery(SELECT_CLASSES)
					.bindResultType(IEntity.class);
			for (IEntity bean : query.evaluate()) {
				if (bean.getURI() == null)
					continue;
				final String namespace = bean.getURI().namespace().toString();
				if (packages.containsKey(namespace)) {
					buildClassOrDatatype(bean, namespace, manager, transformer);
				}
			}
			if (exception != null)
				throw exception;
		} finally {
			manager.close();
		}
	}

	private void buildClassOrDatatype(IEntity bean, String namespace,
			IKommaManager manager, OWL2EcoreTransformer transformer) {
		try {
			transformer.owl2ecore(bean);
		} catch (Exception exc) {
			logger.error("Error processing {}", bean);
			if (exception == null) {
				exception = exc;
			}
		}
	}
}