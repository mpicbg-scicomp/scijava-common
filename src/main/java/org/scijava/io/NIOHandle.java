/*
 * #%L
 * SciJava Common shared library for SciJava software.
 * %%
 * Copyright (C) 2009 - 2015 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.io;

import java.io.IOException;

/**
 * A {@link DataHandle} using buffered <a
 * href="http://javadoc.imagej.net/Java/java/nio/package-summary.html">NIO</a>
 * logic.
 * 
 * @author Chris Allan
 * @author Curtis Rueden
 */
public interface NIOHandle<L extends Location> extends DataHandle<L> {

	/**
	 * Ensures that the file mode is either "r" or "rw".
	 * 
	 * @param mode Mode to validate.
	 * @throws IllegalArgumentException If an illegal mode is passed.
	 */
	void validateMode(final String mode);

	/**
	 * Ensures that the handle has the correct length to be written to and extends
	 * it as required.
	 * 
	 * @param writeLength Number of bytes to write.
	 * @return <code>true</code> if the buffer has not required an extension.
	 *         <code>false</code> otherwise.
	 * @throws IOException If there is an error changing the handle's length.
	 */
	boolean validateLength(final int writeLength) throws IOException;

	/**
	 * Sets the new length of the handle.
	 * 
	 * @param length New length.
	 * @throws IOException If there is an error changing the handle's length.
	 */
	void setLength(long length) throws IOException;

}
