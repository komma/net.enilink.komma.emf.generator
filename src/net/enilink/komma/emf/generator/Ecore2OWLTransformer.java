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

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.ENamedElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import net.enilink.vocab.owl.Class;
import net.enilink.vocab.owl.DatatypeProperty;
import net.enilink.vocab.owl.ObjectProperty;
import net.enilink.vocab.owl.Ontology;
import net.enilink.vocab.owl.OwlProperty;
import net.enilink.vocab.owl.Restriction;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.rdfs.Datatype;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.vocab.xmlschema.XMLSCHEMA;
import net.enilink.komma.concepts.CONCEPTS;
import net.enilink.komma.concepts.IClass;
import net.enilink.komma.concepts.IResource;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IKommaManager;
import net.enilink.komma.core.ILiteral;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;

public class Ecore2OWLTransformer {
	IKommaManager manager;

	public Ecore2OWLTransformer(IKommaManager manager) {
		this.manager = manager;
	}

	public void ecore2OWL(String ecoreFilePath, Map<String, String> options)
			throws OWLTransformerException {
		// register default resource factory
		Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put(
				Resource.Factory.Registry.DEFAULT_EXTENSION,
				new XMIResourceFactoryImpl());

		// create resource set to hold the resource we're loading and its
		// dependent resources
		ResourceSet resourceSet = new ResourceSetImpl();

		// load file
		resourceSet.getResource(
				org.eclipse.emf.common.util.URI.createFileURI(ecoreFilePath),
				true);
		File inputFile = new File(ecoreFilePath);
		org.eclipse.emf.common.util.URI absoluteInputURI = org.eclipse.emf.common.util.URI
				.createFileURI(inputFile.getAbsolutePath());

		Resource resource = resourceSet.getResource(absoluteInputURI, true);

		ecore2OWL(resource, options);

	}

	public void ecore2OWL(Resource ecoreResource, Map<String, String> options)
			throws OWLTransformerException {
		if (options == null) {
			options = Collections.emptyMap();
		}

		EPackage ePackage = (EPackage) ecoreResource.getContents().get(0);

		ecore2OWL(ePackage, options);
	}

	public void ecore2OWL(EPackage ePackage, Map<String, String> options)
			throws OWLTransformerException {
		String nsUri = ePackage.getNsURI();
		// retrieve all eclassifiers needed to be transformed
		List<EClassifier> eClassifiers = getEClassifiers(ePackage);

		// EClass -> OWLClass
		for (EClassifier eclassifier : eClassifiers) {

			// FRANK
			if ("java.util.Map$Entry"
					.equals(eclassifier.getInstanceClassName())) {
				continue;
			}

			// transform classifier
			net.enilink.vocab.rdfs.Resource rclass = eclass2OWL(eclassifier);

			if (eclassifier instanceof EClass) {
				Class oclass = (Class) rclass;

				// eSuperTypes -> RDFSSubClassOf
				EClass eclass = (EClass) eclassifier;
				for (EClassifier eSuper : eclass.getESuperTypes()) {
					if (eSuper.eIsProxy()) {
						continue;
					}
					Class superClass = (Class) eclass2OWL(eSuper);
					oclass.getRdfsSubClassOf().add(superClass);
				}

				// EAttribute -> OWLDatatypeProperty
				for (EAttribute eAttribute : eclass.getEAttributes()) {
					if (eAttribute.eIsProxy()) {
						continue;
					}

					OwlProperty property = eAttribute2Property(eAttribute,
							oclass);
				}

				// EReference -> OWLObjectProperty
				for (EReference eref : eclass.getEReferences()) {
					ObjectProperty op = eref2Property(eref, oclass);
					op.getRdfsDomains().add(oclass);

					/*
					 * determine multiplicity: 5 situation situation lowBound
					 * upperBound 0.. 0 -1 m.. m -1 0..n 0 n m..n m n m..m m m
					 */
					int lowerBound = eref.getLowerBound();
					int upperBound = eref.getUpperBound();
					if (lowerBound > 0) {
						if (upperBound == -1) {
							// minCardinality m..* (m, -1)
							oclass.getRdfsSubClassOf().add(
									createMinCardinarlity(op, lowerBound));
						} else if (lowerBound == upperBound) {
							// Cardinality (m,m)-->m..m
							oclass.getRdfsSubClassOf().add(
									createCardinarlity(op, lowerBound));
						} else {
							// minCardinality & maxCardinality (m,n)-->m..n
							oclass.getRdfsSubClassOf().add(
									createMinCardinarlity(op, lowerBound));
							oclass.getRdfsSubClassOf().add(
									createMaxCardinarlity(op, upperBound));
						}
					} else if (upperBound != -1) {
						// maxCardinality (0,n)-->0..n
						oclass.getRdfsSubClassOf().add(
								createMaxCardinarlity(op, upperBound));
					}
				}
			}
		}

		// Import Komma Ontology
		Ontology ontology = manager.find(URIImpl.createURI(nsUri),
				Ontology.class);

		Ontology kommaOntology = manager.find(
				CONCEPTS.NAMESPACE_URI.trimFragment(), Ontology.class);
		ontology.getOwlImports().add(kommaOntology);

	}

	private IResource eclass2OWL(EClassifier eClassifier)
			throws OWLTransformerException {
		URI uri = getURI(eClassifier);

		IResource resource = null;
		if (!manager.contains(uri)) {
			if (eClassifier instanceof EClass) {
				// EClass -> OWLClass
				EClass eclass = (EClass) eClassifier;
				Class owlClass = manager.createNamed(uri, Class.class);

				resource = (IResource) owlClass;

				// TODO correctly transform all KOMMA specific annotations
				addPropertyValues(resource, RDFS.PROPERTY_COMMENT,
						eAnnotation2Literal(eclass));
			} else if (eClassifier instanceof EEnum) {
				// EEnum -> OWLOneOf
				EEnum eenum = (EEnum) eClassifier;

				Class owlClass = manager.createNamed(uri, Class.class);

				// TODO correctly transform all KOMMA specific annotations
				addPropertyValues(((IResource) owlClass),
						RDFS.PROPERTY_COMMENT, eAnnotation2Literal(eenum));

				// EEnumLiteral -> OWLMember
				for (EEnumLiteral enumLiteral : eenum.getELiterals()) {
					net.enilink.vocab.rdfs.Resource individual = manager
							.createNamed(
									getURI(enumLiteral),
									net.enilink.vocab.rdfs.Resource.class);

					List<Object> oneOf = owlClass.getOwlOneOf();
					if (oneOf == null) {
						oneOf = manager
								.create(net.enilink.vocab.rdf.List.class);
						owlClass.setOwlOneOf(oneOf);
					}
					oneOf.add(individual);
				}

				resource = (IResource) owlClass;
			} else if (eClassifier instanceof EDataType) {
				if (eClassifier.getInstanceClassName().equals("java.util.List")) {
					// TODO
					Class owlClass = manager.createNamed(uri, Class.class);
					owlClass.getRdfsSubClassOf().add(
							(net.enilink.vocab.rdfs.Class) manager
									.find(RDF.TYPE_LIST));
					resource = (IResource) owlClass;
				} else if (eClassifier.getInstanceClassName().equals(
						"java.util.Map")) {
					// Frank
					Class map = handleMap(eClassifier.getName(), eClassifier);
					resource = (IResource) map;
				} else {
					// EDataType -> RDFSDatatype
					Datatype datatype = manager
							.createNamed(uri, Datatype.class);
					resource = (IResource) datatype;
				}
				addPropertyValues(resource, RDFS.PROPERTY_COMMENT,
						eAnnotation2Literal(eClassifier));

			}
		} else {
			resource = manager.find(uri, IResource.class);
		}

		return resource;
	}

	private boolean isObjectDatatype(EDataType eType) {
		if (eType == EcorePackage.eINSTANCE.getEBooleanObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getEFloatObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getEByteObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getEIntegerObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getELongObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getEDoubleObject())
			return true;
		else if (eType == EcorePackage.eINSTANCE.getEShortObject())
			return true;
		else
			return false;
	}

	private URI disjointPropertyName(URI originalName,
			boolean isDatatypeProperty) {
		IEntity resource = manager.find(originalName);
		if (isDatatypeProperty && resource instanceof ObjectProperty) {
			String suffix = "data".equalsIgnoreCase(originalName.localPart()) ? "Value"
					: "Data";
			return originalName.namespace().appendFragment(
					originalName.localPart() + suffix);
		} else if (!isDatatypeProperty && !(resource instanceof ObjectProperty)) {
			String suffix = "data".equalsIgnoreCase(originalName.localPart()) ? "Value"
					: "Data";
			URI newName = originalName.namespace().appendFragment(
					originalName.localPart() + suffix);
			manager.rename(resource, newName);
		}
		return originalName;
	}

	private OwlProperty eAttribute2Property(EAttribute eAttribute,
			Class domainClass) throws OWLTransformerException {
		OwlProperty property = null;
		net.enilink.vocab.rdfs.Class datatype = null;

		EClassifier eType = eAttribute.getEAttributeType();

		boolean createDataTypeProperty = true;
		// TODO check Map$Entry

		if (eType.eIsProxy()) {
			// do nothing
		} else if ("java.util.List".equals(eType.getInstanceClassName())) {
			// OwlKlasse anlegen wie Typ hei�t, subClassof Rdf List
			createDataTypeProperty = false;
			datatype = (net.enilink.vocab.rdfs.Class) eclass2OWL(eType);
		} else if (!(eType instanceof EEnum)) {
			URI xsdUri = determineXsdDatatype(eType);

			URI typeURI;
			if (xsdUri != null) {
				typeURI = xsdUri;
				datatype = manager.createNamed(typeURI, Datatype.class);
			} else {
				// unknown user-defined datatype
				typeURI = getURI(eType);
				datatype = manager.createNamed(typeURI, Class.class);

				createDataTypeProperty = false;
			}
		} else {
			EEnum eEnum = (EEnum) eType;
			datatype = eEnum2oneOf(eEnum);

			createDataTypeProperty = false;
		}

		URI name = disjointPropertyName(getURI(eAttribute),
				createDataTypeProperty);
		if (createDataTypeProperty) {
			property = manager.createNamed(name, DatatypeProperty.class);
		} else {
			property = manager.createNamed(name, ObjectProperty.class);
		}

		if (datatype != null) {
			property.getRdfsRanges().add(datatype);
		}

		if (domainClass != null && datatype != null) {
			Restriction restriction = manager.create(Restriction.class);
			restriction.setOwlOnProperty(property);
			restriction.setOwlAllValuesFrom(datatype);
			domainClass.getRdfsSubClassOf().add(restriction);
		}

		if (domainClass != null) {
			property.getRdfsDomains().add(domainClass);

			// Kardinalit�t
			int lowerBound, upperBound;
			if (!isObjectDatatype(eAttribute.getEAttributeType())) {
				lowerBound = upperBound = 1;
			} else {
				lowerBound = eAttribute.getLowerBound();
				upperBound = eAttribute.getUpperBound();
			}
			if (lowerBound > 0) {
				if (upperBound == -1) {
					// minCardinality m..* (m, -1)
					domainClass.getRdfsSubClassOf().add(
							createMinCardinarlity(property, lowerBound));
				} else if (lowerBound == upperBound) {
					// Cardinality (m,m)-->m..m
					domainClass.getRdfsSubClassOf().add(
							createCardinarlity(property, lowerBound));
				} else {
					// minCardinality & maxCardinality (m,n)-->m..n
					domainClass.getRdfsSubClassOf().add(
							createMinCardinarlity(property, lowerBound));
					domainClass.getRdfsSubClassOf().add(
							createMaxCardinarlity(property, upperBound));
				}
			} else if (upperBound != -1) {
				// maxCardinality (0,n)-->0..n
				domainClass.getRdfsSubClassOf().add(
						createMaxCardinarlity(property, upperBound));
			}
		}
		return property;
	}

	private URI determineXsdDatatype(EClassifier eType) {
		URI xsdUri = null;
		// check if XSD datatype
		if (eType == EcorePackage.eINSTANCE.getEBooleanObject()
				|| eType == EcorePackage.eINSTANCE.getEBoolean())
			xsdUri = XMLSCHEMA.TYPE_BOOLEAN;
		else if (eType == EcorePackage.eINSTANCE.getEFloatObject()
				|| eType == EcorePackage.eINSTANCE.getEFloat())
			xsdUri = XMLSCHEMA.TYPE_FLOAT;
		else if (eType == EcorePackage.eINSTANCE.getEByteObject()
				|| eType == EcorePackage.eINSTANCE.getEByte())
			xsdUri = XMLSCHEMA.TYPE_BYTE;
		else if (eType == EcorePackage.eINSTANCE.getEInt()
				|| eType == EcorePackage.eINSTANCE.getEIntegerObject())
			xsdUri = XMLSCHEMA.TYPE_INT;
		else if (eType == EcorePackage.eINSTANCE.getELongObject()
				|| eType == EcorePackage.eINSTANCE.getELong())
			xsdUri = XMLSCHEMA.TYPE_LONG;
		else if (eType == EcorePackage.eINSTANCE.getEDoubleObject()
				|| eType == EcorePackage.eINSTANCE.getEDouble())
			xsdUri = XMLSCHEMA.TYPE_DOUBLE;
		else if (eType == EcorePackage.eINSTANCE.getEShortObject()
				|| eType == EcorePackage.eINSTANCE.getEShort())
			xsdUri = XMLSCHEMA.TYPE_SHORT;
		/*
		 * else if (eType == EcorePackage.eINSTANCE.getEIntegerObject()) xsdUri
		 * = XMLSCHEMA.TYPE_INTEGER;
		 */
		else if (eType == EcorePackage.eINSTANCE.getEString())
			xsdUri = XMLSCHEMA.TYPE_STRING;
		return xsdUri;
	}

	private Class eEnum2oneOf(EEnum eEnum) {
		IEntity resource = manager.find(getURI(eEnum));

		if (!(resource instanceof Class)) {
			net.enilink.vocab.owl.Class owlClass = manager
					.designateEntity(resource, Class.class);
			resource = owlClass;

			List<Object> memberList = new ArrayList<Object>();

			for (EEnumLiteral eEnumLiteral : eEnum.getELiterals()) {
				String name = eEnumLiteral.getName();
				// TODO create literals
			}
			if (memberList != null && !memberList.isEmpty()) {
				// eenum = EcoreFactory.eINSTANCE.createEEnum();
				// eenum.setName(getName(enumclass));
				// eenum.getEAnnotations().addAll(createEAnnotations(enumclass));
				//
				// // create enumliterals from enumclass members
				// int intValue = 0;
				// for (Iterator<Object> it = memberList.iterator();
				// it.hasNext();) {
				// Object obj = it.next();
				//
				// // test whether contains label "EEnum"
				// String enumeration = (obj instanceof Resource ? toUri(
				// ((Resource) obj).getQName()).toString() : String
				// .valueOf(obj));
				//
				// // create EEnumLiteral
				// EEnumLiteral eliteral = EcoreFactory.eINSTANCE
				// .createEEnumLiteral();
				// if (obj instanceof Resource) {
				// eliteral.getEAnnotations().addAll(
				// createEAnnotations((Resource) obj));
				// }
				//
				// eliteral.setName(enumeration);
				// eliteral.setValue(intValue);
				// eenum.getELiterals().add(eliteral);
				// intValue++;
				// }
			}
		}

		return (Class) resource;
	}

	private ObjectProperty eref2Property(EReference eReference,
			Class domainClass) throws OWLTransformerException {
		URI uri = disjointPropertyName(getURI(eReference), false);

		IEntity resource = manager.find(uri);
		ObjectProperty objectProperty;
		if (resource == null || !(resource instanceof ObjectProperty)) {
			// new property
			objectProperty = manager.createNamed(uri, ObjectProperty.class);
		} else {
			objectProperty = (ObjectProperty) resource;
		}

		// range
		EClassifier eRange = eReference.getEReferenceType();
		if (!eRange.eIsProxy()) {
			net.enilink.vocab.owl.Class range = null;
			if (!"java.util.Map$Entry".equals(eRange.getInstanceClassName())) {
				range = (net.enilink.vocab.owl.Class) eclass2OWL(eRange);
				objectProperty.getRdfsRanges().add(range);
			} else {
				range = handleMap(eReference, eRange);
				objectProperty.getRdfsRanges().add(range);
				System.out.println(domainClass + " : " + range);
			}

			if (domainClass != null && range != null) {
				Restriction restriction = manager.create(Restriction.class);
				restriction.setOwlOnProperty(objectProperty);
				restriction.setOwlAllValuesFrom(range);
				domainClass.getRdfsSubClassOf().add(restriction);
			}

		}

		return objectProperty;
	}

	private Class handleMap(EReference eReference, EClassifier eRange)
			throws OWLTransformerException {
		String name = eReference.getName();
		String pName = ((ENamedElement) eReference.eContainer()).getName();

		pName = pName + name.substring(0, 1).toUpperCase() + name.substring(1);

		return handleMap(pName, eRange);
	}

	private Class handleMap(String name, EClassifier eClassifier)
			throws OWLTransformerException {
		Class owlClass = manager.createNamed(
				getURI(name, eClassifier.getEPackage()), Class.class);

		DetermineMapClassResult determineMapClassResult = determineMapClass(eClassifier);

		if (determineMapClassResult != null) {
			owlClass.getRdfsSubClassOf().add(
					(Class) determineMapClassResult.clazz);

			if (determineMapClassResult.keyClass != null) {

				Restriction restriction = manager.create(Restriction.class);

				Restriction keyDataRestriction = manager
						.create(Restriction.class);
				keyDataRestriction.setOwlOnProperty(manager.find(
						CONCEPTS.PROPERTY_KEYDATA, OwlProperty.class));

				// QName qn = new
				// QName(determineMapClassResult.xsdUriKey.getNamespace(),determineMapClassResult.xsdUriKey.getLocalName());
				// System.out.println("super: "+determineMapClassResult.xsdUriKey);
				// wichtig:
				// (net.enilink.vocab.rdfs.Class)manager.find(qn)
				keyDataRestriction
						.setOwlAllValuesFrom(determineMapClassResult.keyClass);

				restriction.setOwlOnProperty(manager.find(
						CONCEPTS.PROPERTY_ENTRY, OwlProperty.class));
				restriction.setOwlAllValuesFrom(keyDataRestriction);

				owlClass.getRdfsSubClassOf().add(restriction);
			}

			if (determineMapClassResult.valueClass != null) {

				Restriction restriction = manager.create(Restriction.class);

				Restriction keyDataRestriction = manager
						.create(Restriction.class);
				keyDataRestriction.setOwlOnProperty(manager.find(
						CONCEPTS.PROPERTY_VALUEDATA, OwlProperty.class));

				// QName qn = new
				// QName(determineMapClassResult.xsdUriValue.getNamespace(),determineMapClassResult.xsdUriValue.getLocalName());
				// IEntity ruff = manager.find(qn);
				// System.out.println(Arrays.toString(ruff.getClass().getInterfaces()));
				// (net.enilink.vocab.rdfs.Class)manager.find(qn)
				keyDataRestriction
						.setOwlAllValuesFrom(determineMapClassResult.valueClass);

				restriction.setOwlOnProperty(manager.find(
						CONCEPTS.PROPERTY_ENTRY, OwlProperty.class));
				restriction.setOwlAllValuesFrom(keyDataRestriction);

				owlClass.getRdfsSubClassOf().add(restriction);
			}

		}

		return owlClass;
	}

	private DetermineMapClassResult determineMapClass(EClassifier eClassifier)
			throws OWLTransformerException {
		DetermineMapClassResult result = new DetermineMapClassResult();

		EStructuralFeature key = null, value = null;
		if (eClassifier instanceof EClass) {
			for (EStructuralFeature feature : ((EClass) eClassifier)
					.getEAllStructuralFeatures()) {
				if ("key".equals(feature.getName())) {
					key = feature;
				} else if ("value".equals(feature.getName())) {
					value = feature;
				}
			}

			if (key != null && value != null) {
				if (key instanceof EAttribute && value instanceof EAttribute) {

					result.clazz = getMapClass("LiteralKeyValueMap");
					result.keyClass = manager.find(
							determineXsdDatatype(((EAttribute) (key))
									.getEAttributeType()), IClass.class);
					result.valueClass = manager.find(
							determineXsdDatatype(((EAttribute) (value))
									.getEAttributeType()), IClass.class);
				} else if (key instanceof EAttribute
						&& value instanceof EReference) {
					// LiteralKeyMap

					result.clazz = getMapClass("LiteralKeyMap");
					result.keyClass = determineDatatype(((EAttribute) (key))
							.getEAttributeType());

				} else if (key instanceof EReference
						&& value instanceof EAttribute) {
					EClassifier ec = ((EAttribute) value).getEAttributeType();
					result.valueClass = determineDatatype(ec);
					result.clazz = getMapClass("LiteralValueMap");
				} else if (key instanceof EReference
						&& value instanceof EReference) {
					// KeyValueMap
					result.clazz = getMapClass("KeyValueMap");
				}
			} else {
				result = new DetermineMapClassResult();
				result.clazz = getMapClass("KeyValueMap");
			}
		} else {
			result.clazz = getMapClass("KeyValueMap");
		}

		return result;
	}

	private net.enilink.vocab.rdfs.Class determineDatatype(
			EClassifier classifier) throws OWLTransformerException {
		net.enilink.vocab.rdfs.Class result = null;
		URI xsdUri = determineXsdDatatype(classifier);
		if (xsdUri == null) {
			eclass2OWL(classifier);
			// Vielleicht ist es ein Typ aus der Ontologie
			result = manager.find(getURI(classifier), IClass.class);
		} else {
			result = manager.find(xsdUri, IClass.class);
		}

		return result;
	}

	private Class getMapClass(String mapName) {
		return manager.find(CONCEPTS.NAMESPACE_URI.appendFragment(mapName),
				Class.class);
	}

	private URI getURI(ENamedElement elem) {
		EObject nsOwner = elem;

		String ns = null;
		// use EPackage's Namespace
		while (nsOwner != null) {
			if (nsOwner instanceof EPackage) {
				ns = ((EPackage) nsOwner).getNsURI();
				break;
			} else {
				nsOwner = nsOwner.eContainer();
			}
		}

		return URIImpl.createURI(ns).appendFragment(elem.getName());
	}

	private URI getURI(String name, ENamedElement parent) {
		EObject nsOwner = parent;

		String ns = null;
		if (nsOwner instanceof EPackage) {
			ns = ((EPackage) nsOwner).getNsURI();
		} else {
			nsOwner = nsOwner.eContainer();
		}

		return URIImpl.createURI(ns).appendFragment(name);
	}

	private void addPropertyValues(IResource resource, IReference property,
			List<?> values) {
		for (Object value : values) {
			resource.addProperty(property, value);
		}
	}

	/**
	 * 
	 * @param eNamedElement
	 * @return a list of literals for annotations of eNamedElement
	 */
	private List<ILiteral> eAnnotation2Literal(ENamedElement eNamedElement) {
		List<ILiteral> annotations = new ArrayList<ILiteral>();

		for (EAnnotation eAnnotation : eNamedElement.getEAnnotations()) {
			if (eAnnotation != null) {
				ILiteral literal = manager.createLiteral(
						eAnnotation2String(eAnnotation), null, null);
				annotations.add(literal);
			}
		}

		return annotations;
	}

	private String eAnnotation2String(EAnnotation annotation) {
		String str = annotation.getSource() + ": ";
		EMap<String, String> details = annotation.getDetails();
		for (Iterator<?> iter = details.keySet().iterator(); iter.hasNext();) {
			Object key = iter.next();
			str += "  " + key + ":" + details.get(key);
		}
		return replaceKeywords(str);
	}

	private String replaceKeywords(String str) {
		str = str.replaceAll("&", "&amp;");
		str = str.replaceAll("<", "&lt;");
		str = str.replaceAll(">", "&gt;");
		str = str.replaceAll("'", "&apos;");
		str = str.replaceAll("\"", "&quot;");

		return str;
	}

	private Restriction createMinCardinarlity(OwlProperty property, int bound)
			throws OWLTransformerException {
		Restriction minCr = property.getKommaManager()
				.create(Restriction.class);

		minCr.setOwlOnProperty(property);
		minCr.setOwlMinCardinality(BigInteger.valueOf(bound));

		return minCr;
	}

	private Restriction createMaxCardinarlity(OwlProperty property, int bound)
			throws OWLTransformerException {
		Restriction maxCr = property.getKommaManager()
				.create(Restriction.class);

		maxCr.setOwlOnProperty(property);
		maxCr.setOwlMaxCardinality(BigInteger.valueOf(bound));

		return maxCr;
	}

	private Restriction createCardinarlity(OwlProperty property, int bound)
			throws OWLTransformerException {
		Restriction cr = property.getKommaManager().create(Restriction.class);

		cr.setOwlOnProperty(property);
		cr.setOwlCardinality(BigInteger.valueOf(bound));

		return cr;
	}

	List<EClassifier> getEClassifiers(EPackage ePackage) {
		List<EClassifier> lst = ePackage.getEClassifiers();

		for (EPackage eSubPackage : ePackage.getESubpackages()) {
			lst.addAll(getEClassifiers(eSubPackage));
		}

		return lst;
	}

	private class DetermineMapClassResult {
		Class clazz;
		net.enilink.vocab.rdfs.Class keyClass;
		// URI xsdUriKey;
		net.enilink.vocab.rdfs.Class valueClass;
		// URI xsdUriValue;
	}

}
