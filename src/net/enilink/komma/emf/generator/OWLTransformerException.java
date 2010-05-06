/**
 * <copyright> 
 * 
 * Copyright (c) 2004, 2010 IBM Corporation and others. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License - v 1.0 
 * which accompanies this distribution, and is available at 
 * http://opensource.org/licenses/eclipse-1.0.txt 
 * 
 * Contributors: 
 *   IBM - Initial API and implementation 
 * 
 * </copyright> 
 * 
 * $Id: EODMOWLTransformerException.java,v 1.1 2007/03/18 09:07:08 lzhang Exp $
 */

package net.enilink.komma.emf.generator;

public class OWLTransformerException extends Exception {

	private static final long serialVersionUID = 278004641221903219L;

	/**
	 * Constructor.
	 * 
	 * @param message
	 *            errror message
	 */
	public OWLTransformerException(String message) {
		super(message);
	}

	/**
	 * Default constructor
	 * 
	 */
	public OWLTransformerException() {
		super();
	}
}
