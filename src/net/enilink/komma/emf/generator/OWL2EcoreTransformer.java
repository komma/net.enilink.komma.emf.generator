/*******************************************************************************
 * Copyright (c) 2009, 2010 Fraunhofer IWU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Fraunhofer IWU - initial API and implementation
 *******************************************************************************/
package net.enilink.komma.emf.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;

import net.enilink.vocab.owl.DataRange;
import net.enilink.vocab.owl.FunctionalProperty;
import net.enilink.vocab.owl.InverseFunctionalProperty;
import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.owl.ObjectProperty;
import net.enilink.vocab.owl.SymmetricProperty;
import net.enilink.vocab.owl.TransitiveProperty;
import net.enilink.vocab.rdf.Property;
import net.enilink.vocab.rdfs.Datatype;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.vocab.xmlschema.XMLSCHEMA;
import net.enilink.komma.concepts.IClass;
import net.enilink.komma.concepts.IProperty;
import net.enilink.komma.concepts.IResource;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.ILiteral;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URI;

public class OWL2EcoreTransformer {
	Map<String, String> packages;
	Map<String, EPackage> ePackages;
	Map<IEntity, EModelElement> owl2ecoreMap = new HashMap<IEntity, EModelElement>();

	public OWL2EcoreTransformer(Map<String, EPackage> ePackages,
			Map<String, String> packages) {
		this.ePackages = ePackages;
		this.packages = packages;
	}

	private EPackage ensurePackage(String namespace) {
		EPackage ePackage = ePackages.get(namespace);
		if (ePackage == null) {
			ePackage = EcoreFactory.eINSTANCE.createEPackage();
			if (packages.containsKey(namespace)) {
				ePackage.setName(packages.get(namespace).replaceAll("[.]", "_"));
				ePackage.setNsPrefix(packages.get(namespace).replaceAll("[.]",
						"_"));
			} else {
				ePackage.setName(namespace);
				ePackage.setNsPrefix(namespace);
			}
			ePackage.setNsURI(namespace);
			ePackages.put(namespace, ePackage);
		}

		return ePackage;
	}

	private Iterator<?> getPropertiesForClass(
			net.enilink.vocab.rdfs.Class clazz) {
		IQuery<?> query = clazz
				.getKommaManager()
				.createQuery(
						"PREFIX rdfs: <"
								+ RDFS.NAMESPACE
								+ "> "
								+ "SELECT DISTINCT ?prop WHERE { ?prop rdfs:domain ?clazz }");
		query.setParameter("clazz", clazz);
		return query.evaluate();
	}

	protected EAnnotation createAnnotation(Object uri) {
		return createAnnotation(uri, null);
	}

	protected EAnnotation createAnnotation(Object uri, Object value) {
		EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
		annotation.setSource(String.valueOf(uri));
		if (value != null) {
			annotation.getDetails().put("value", String.valueOf(value));
		}
		return annotation;
	}

	protected void addAnnotation(EModelElement eElement, Object uri) {
		addAnnotation(eElement, uri, null);
	}

	protected void addAnnotation(EModelElement eElement, Object uri,
			String value) {
		eElement.getEAnnotations().add(createAnnotation(uri, value));
	}

	protected void addComment(EModelElement eElement, String comment) {
		addAnnotation(eElement, RDFS.PROPERTY_COMMENT.toString(), comment);
	}

	protected boolean isBuiltInResource(IEntity entity) {
		String namespaceUri = entity.getURI().namespace().toString();
		return "http://www.w3.org/2002/07/owl#".equals(namespaceUri)
				|| "http://www.w3.org/2000/01/rdf-schema#".equals(namespaceUri);
	}

	public void owl2ecore(IEntity bean) throws OWLTransformerException {
		// don't map RDFS or OWL built-in types
		if (isBuiltInResource(bean)) {
			return;
		}

		EClassifier eClass = owl2eclassifier(bean);
		if (eClass == null) {
			return;
		}

		if (bean instanceof net.enilink.vocab.rdfs.Class) {
			net.enilink.vocab.owl.Class owlClass = (net.enilink.vocab.owl.Class) bean;

			// RDFSSubClassOf -> EClass.eSuperType
			for (net.enilink.vocab.rdfs.Class owlSuperClass : owlClass
					.getRdfsSubClassOf()) {
				if (!isNamedResource(owlSuperClass)
						|| isBuiltInResource(owlSuperClass)) {
					continue;
				}
				if (!owlSuperClass.equals(owlClass)
						&& isNamedResource(owlSuperClass)) {
					EClass eSuperClass = (EClass) owl2eclassifier(owlSuperClass);

					// check whether eclass if father of eSuperClass
					if (eSuperClass.getESuperTypes().contains(eClass)) {
						eSuperClass.getESuperTypes().remove(eClass);

						addAnnotation(eClass, OWL.PROPERTY_EQUIVALENTCLASS,
								eSuperClass.getName());
						addAnnotation(eSuperClass,
								OWL.PROPERTY_EQUIVALENTCLASS, eClass.getName());
					} else if (eClass instanceof EClass) {
						((EClass) eClass).getESuperTypes().add(eSuperClass);
					}
				}
			}

			// property -> EReference, Attribute
			for (Iterator<?> propertiesIt = getPropertiesForClass(owlClass); propertiesIt
					.hasNext();) {
				IProperty property = (IProperty) propertiesIt.next();

				EStructuralFeature eProperty = prop2EStructuralFeature(property);
				if (property instanceof ObjectProperty) {
					Set<ObjectProperty> inverseProperties = ((ObjectProperty) property)
							.getOwlInverseOf();
					if (inverseProperties != null) {
						for (ObjectProperty inverseProperty : inverseProperties) {
							Set<net.enilink.vocab.rdfs.Class> rdfsDomains = inverseProperty
									.getRdfsDomains();
							if (rdfsDomains != null && !rdfsDomains.isEmpty()) {
								EReference eInverseProperty = (EReference) prop2EStructuralFeature((IProperty) inverseProperty);
								eInverseProperty
										.setEOpposite((EReference) eProperty);
								((EReference) eProperty)
										.setEOpposite(eInverseProperty);
							}
						}
					}
				}

				// set domain
				((EClass) eClass).getEStructuralFeatures().add(eProperty);
			}
		}
	}

	private boolean isNamedResource(IEntity resource) {
		return resource.getURI() != null;
	}

	private EStructuralFeature prop2EStructuralFeature(IProperty p) {
		EStructuralFeature ep = (EStructuralFeature) owl2ecoreMap.get(p);
		if (ep == null) {
			Set<net.enilink.vocab.rdfs.Class> range = p
					.getRdfsRanges();
			EClassifier eRange = null;
			if (range != null && !range.isEmpty()) {
				// if property without range, leave it as null
				eRange = (EClassifier) owl2ecoreMap
						.get(range.iterator().next());

				net.enilink.vocab.rdfs.Class rangeClass = range
						.iterator().next();
				if (rangeClass.getURI() != null) {
					eRange = owl2eclassifier(rangeClass);
				}
			}

			if (p instanceof ObjectProperty && !(eRange instanceof EEnum)) {
				ep = EcoreFactory.eINSTANCE.createEReference();
				ep.setUpperBound(-1);

				// property attribute to annotation
				if (p instanceof TransitiveProperty) {
					addAnnotation(ep, OWL.TYPE_TRANSITIVEPROPERTY);
				}

				if (p instanceof SymmetricProperty) {
					addAnnotation(ep, OWL.TYPE_SYMMETRICPROPERTY);
				}

				if (p instanceof InverseFunctionalProperty) {
					addAnnotation(ep, OWL.TYPE_INVERSEFUNCTIONALPROPERTY);
				}
			} else {
				// Datatype Property
				ep = EcoreFactory.eINSTANCE.createEAttribute();
			}

			ep.setName(getName(p));
			ep.getEAnnotations().addAll(createEAnnotations(p));
			ep.setEType(eRange);

			// subPropertyOf will be in eannoations
			for (Property superProperty : p.getRdfsSubPropertyOf()) {
				if (superProperty != null) {
					addAnnotation(ep, RDFS.PROPERTY_SUBPROPERTYOF,
							getName(superProperty));
				}
			}
			// property attribute to annotation
			if (p instanceof FunctionalProperty) {
				addAnnotation(ep, OWL.TYPE_FUNCTIONALPROPERTY);
			}

			owl2ecoreMap.put(p, ep);
		}

		return ep;
	}

	private static boolean isEnumeratedClass(
			net.enilink.vocab.owl.Class owlClass) {
		List<?> oneOf = owlClass.getOwlOneOf();
		return oneOf != null && !oneOf.isEmpty();
	}

	private EClassifier owl2eclassifier(IEntity oclass) {
		EClassifier eclass = (EClassifier) owl2ecoreMap.get(oclass);

		if (eclass == null) {
			boolean createdType = true;

			if (oclass instanceof DataRange
					|| (oclass instanceof IClass && (isEnumeratedClass((IClass) oclass)))) {
				// enumerate, datarange -> eenum
				eclass = oneof2EEnum((IClass) oclass);
			} else if (oclass instanceof Datatype) {
				Datatype dt = (Datatype) oclass;
				URI dtURI = dt.getURI();
				if (dtURI != null) {
					createdType = false;

					if (dtURI.equals(XMLSCHEMA.TYPE_BOOLEAN))
						eclass = EcorePackage.eINSTANCE.getEBoolean();
					else if (dtURI.equals(XMLSCHEMA.TYPE_FLOAT))
						eclass = EcorePackage.eINSTANCE.getEFloat();
					else if (dtURI.equals(XMLSCHEMA.TYPE_INT)
							|| dtURI.equals(XMLSCHEMA.TYPE_INTEGER))
						eclass = EcorePackage.eINSTANCE.getEInt();
					else if (dtURI.equals(XMLSCHEMA.TYPE_BYTE))
						eclass = EcorePackage.eINSTANCE.getEByte();
					else if (dtURI.equals(XMLSCHEMA.TYPE_LONG))
						eclass = EcorePackage.eINSTANCE.getELong();
					else if (dtURI.equals(XMLSCHEMA.TYPE_DOUBLE))
						eclass = EcorePackage.eINSTANCE.getEDouble();
					else if (dtURI.equals(XMLSCHEMA.TYPE_SHORT))
						eclass = EcorePackage.eINSTANCE.getEShort();
					else if (dtURI.equals(XMLSCHEMA.TYPE_STRING)) {
						// || dtURI.equals(RDFS.CR_LITERAL_STR)
						// || dtURI.equals(RDF.C_XMLLITERAL_STR))
						eclass = EcorePackage.eINSTANCE.getEString();
						// else if( dt.getRDFSisDefinedBy().size()>0 &&
						// DataRange.class.isInstance(
						// dt.getRDFSisDefinedBy().get(0)) ) {
						// eclass = oneof2EEnum((RDFSClass)
						// dt.getRDFSisDefinedBy().get(0), owc2ecMap);
						// }
					} else {
						// System.out.println("creating type: " +
						// getName(oclass)
						// + " from: " + dtURI);
						// Create a user-defined EDatatType
						eclass = EcoreFactory.eINSTANCE.createEDataType();
						eclass.setName(getName(oclass));
						eclass.getEAnnotations().addAll(
								createEAnnotations((IResource) dt));

						createdType = true;
					}
				}
			} else {
				// Class
				eclass = EcoreFactory.eINSTANCE.createEClass();

				// create name for eclass
				eclass.setName(getName(oclass));
				eclass.getEAnnotations().addAll(
						createEAnnotations((IResource) oclass));
			}

			if (eclass != null) {
				owl2ecoreMap.put(oclass, eclass);

				if (createdType) {
					ensurePackage(oclass.getURI().namespace().toString())
							.getEClassifiers().add(eclass);
				}
			}
		}

		return eclass;
	}

	private static String getName(Object object) {
		return object instanceof IEntity ? ((IEntity) object).getURI()
				.localPart() : String.valueOf(object);
	}

	private List<EAnnotation> createEAnnotations(IResource rs) {
		List<EAnnotation> annotations = new ArrayList<EAnnotation>();

		// create comments as annotations
		for (Object value : rs.getPropertyValues(RDFS.PROPERTY_COMMENT, true)) {
			annotations.add(createAnnotation(RDFS.PROPERTY_COMMENT,
					((ILiteral) value).getLabel()));
		}

		// create isDefinedBy as annotations
		for (Object definedBy : rs.getRdfsIsDefinedBy()) {
			if (definedBy instanceof IEntity) {
				annotations.add(createAnnotation(RDFS.PROPERTY_ISDEFINEDBY,
						((IEntity) definedBy).getURI()));
			}
		}

		// create Label as annotations
		for (Object value : rs.getPropertyValues(RDFS.PROPERTY_LABEL, true)) {
			annotations.add(createAnnotation(RDFS.PROPERTY_LABEL,
					((ILiteral) value).getLabel()));
		}

		if (rs instanceof net.enilink.vocab.owl.Class) {
			net.enilink.vocab.owl.Class owlClass = (net.enilink.vocab.owl.Class) rs;

			// record disjointWith
			for (net.enilink.vocab.owl.Class owlDisjointClass : owlClass
					.getOwlDisjointWith()) {
				if (owlDisjointClass != null) {
					annotations.add(createAnnotation(OWL.PROPERTY_DISJOINTWITH,
							owlDisjointClass.getURI()));
				}
			}
		}

		return annotations;
	}

	private EEnum oneof2EEnum(IClass enumclass) {
		EEnum eenum = (EEnum) owl2ecoreMap.get(enumclass);

		if (eenum == null) {
			List<Object> memberList = enumclass.getOwlOneOf();

			if (memberList != null && !memberList.isEmpty()) {
				eenum = EcoreFactory.eINSTANCE.createEEnum();
				eenum.setName(getName(enumclass));
				eenum.getEAnnotations().addAll(createEAnnotations(enumclass));

				// create enumliterals from enumclass members
				int intValue = 0;
				for (Iterator<Object> it = memberList.iterator(); it.hasNext();) {
					Object object = it.next();

					// create EEnumLiteral
					EEnumLiteral eliteral = EcoreFactory.eINSTANCE
							.createEEnumLiteral();
					if (object instanceof IResource) {
						eliteral.getEAnnotations().addAll(
								createEAnnotations((IResource) object));
					}

					eliteral.setName(getName(object));
					eliteral.setValue(intValue);
					eenum.getELiterals().add(eliteral);
					intValue++;
				}
			}
		}

		return eenum;
	}
}
