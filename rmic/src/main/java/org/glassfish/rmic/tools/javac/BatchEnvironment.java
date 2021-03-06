/*
 * Copyright (c) 1994, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.glassfish.rmic.tools.javac;

import org.glassfish.rmic.BatchEnvironmentError;
import org.glassfish.rmic.asm.AsmClassFactory;
import org.glassfish.rmic.tools.binaryclass.BinaryClassFactory;
import org.glassfish.rmic.tools.java.*;
import org.glassfish.rmic.tools.java.Package;
import org.glassfish.rmic.tools.tree.Node;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.stream.Collectors;

import static java.lang.Character.isDigit;

/**
 * Main environment of the batch version of the Java compiler,
 * this needs more work.
 *
 * WARNING: The contents of this source file are not part of any
 * supported API.  Code that depends on them does so at its own risk:
 * they are subject to change or removal without notice.
 */
@Deprecated
public
class BatchEnvironment extends Environment implements ErrorConsumer {
    private static final String USE_LEGACY_PARSING_PROPERTY = "org.glassfish.rmic.UseLegacyClassParsing";
    private static final String JAVA_VERSION_PROPERTY = "java.version";
    private static final int ASM_ONLY_JAVA_VERSION = 10;
    private static final ClassDefinitionFactory classDefinitionFactory = createClassDefinitionFactory();

    static ClassDefinitionFactory createClassDefinitionFactory() {
        try {
            return useBinaryClassFactory() ? new BinaryClassFactory() : new AsmClassFactory();
        } catch (NoClassDefFoundError e) {
            if (!mayUseBinaryClassFactory())
                throw new BatchEnvironmentError("RMIC is unable to parse class files at this JDK level without an appropriate version of ASM in its class path");

            return new BinaryClassFactory();
        }
    }

    private static boolean useBinaryClassFactory() {
        return Boolean.getBoolean(USE_LEGACY_PARSING_PROPERTY) && mayUseBinaryClassFactory();
    }

    private static boolean mayUseBinaryClassFactory() {
        return isBinaryClassCompatibleJavaVersion(System.getProperty(JAVA_VERSION_PROPERTY));
    }

    private static boolean isBinaryClassCompatibleJavaVersion(String property) {
        return property.startsWith("1.") || getVersionPortion(property) < ASM_ONLY_JAVA_VERSION;
    }

    private static int getVersionPortion(String versionString) {
        for (int i = 0; i < versionString.length(); i++)
            if (!isDigit(versionString.charAt(i)))
                return Integer.parseInt(versionString.substring(0, i));
        
        return Integer.parseInt(versionString);
    }

    /**
     * The stream where error message are printed.
     */
    private OutputStream out;

    /**
     * The path we use for finding class (binary) files.
     */
    protected ClassPath binaryPath;

    /**
     * A hashtable of resource contexts.
     */
    private Hashtable<Identifier, Package> packages = new Hashtable<>(31);

    /**
     * The classes, in order of appearance.
     */
    private Vector<ClassDeclaration> classesOrdered = new Vector<>();

    /**
     * The classes, keyed by ClassDeclaration.
     */
    private Hashtable<Type, ClassDeclaration> classes = new Hashtable<>(351);

    /**
     * flags
     */
    public int flags;

    /**
     * Major and minor versions to use for generated class files.
     * Environments that extend BatchEnvironment (such as javadoc's
     * Env class) get the default values below.
     *
     * javac itself may override these versions with values determined
     * from the command line "-target" option.
     */
    public short majorVersion = JAVA_DEFAULT_VERSION;
    public short minorVersion = JAVA_DEFAULT_MINOR_VERSION;

// JCOV
    /**
     * coverage data file
     */
    private File covFile;
// end JCOV

    /**
     * The number of errors and warnings
     */
    public int nerrors;
    public int nwarnings;
    private int ndeprecations;

    /**
     * A list of files containing deprecation warnings.
     */
    private Vector<Object> deprecationFiles = new Vector<>();

        /**
         * writes out error messages
         */

    private ErrorConsumer errorConsumer;

    public BatchEnvironment(OutputStream out,
                            ClassPath binaryPath) {
        this(out, binaryPath, null);
    }

    public BatchEnvironment(OutputStream out,
                            ClassPath binaryPath,
                            ErrorConsumer errorConsumer) {
        this.out = out;
        this.binaryPath = binaryPath;
        this.errorConsumer = (errorConsumer == null) ? this : errorConsumer;
    }

    /**
     * Return flags
     */
    public int getFlags() {
        return flags;
    }

    /**
     * Return major version to use for generated class files
     */
    public short getMajorVersion() {
        return majorVersion;
    }

    /**
     * Return minor version to use for generated class files
     */
    public short getMinorVersion() {
        return minorVersion;
    }

// JCOV
    /**
     * Return coverage data file
     */
    public File getcovFile() {
        return covFile;
    }
// end JCOV

    /**
     * Return an enumeration of all the currently defined classes
     * in order of appearance to getClassDeclaration().
     */
    public Enumeration<ClassDeclaration> getClasses() {
        return classesOrdered.elements();
    }

    /**
     * Return the declarations for all generated classes. These can be recognized
     * as having the 'parsed' status
     */
    public Iterable<ClassDeclaration> getGeneratedClasses() {
        return classesOrdered.stream()
                .filter(p -> p.getStatus() == Constants.CS_PARSED)
                .collect(Collectors.toList());
    }

    /**
     * A set of Identifiers for all packages exempt from the "exists"
     * check in Imports#resolve().  These are the current packages for
     * all classes being compiled as of the first call to isExemptPackage.
     */
    private Set<Identifier> exemptPackages;

    /**
     * Tells whether an Identifier refers to a package which should be
     * exempt from the "exists" check in Imports#resolve().
     */
    public boolean isExemptPackage(Identifier id) {
        if (exemptPackages == null) {
            // Collect a list of the packages of all classes currently
            // being compiled.
            setExemptPackages();
        }

        return exemptPackages.contains(id);
    }

    /**
     * Set the set of packages which are exempt from the exists check
     * in Imports#resolve().
     */
    private void setExemptPackages() {
        // The JLS gives us the freedom to define "accessibility" of
        // a package in whatever manner we wish.  After the evaluation
        // of bug 4093217, we have decided to consider a package P
        // accessible if either:
        //
        // 1. The directory corresponding to P exists on the classpath.
        // 2. For any class C currently being compiled, C belongs to
        //    package P.
        // 3. For any class C currently being compiled, C belongs to
        //    package Q and Q is a subpackage of P.
        //
        // In order to implement this, we collect the current packages
        // (and prefixes) of all packages we have found so far.  These
        // will be exempt from the "exists" check in
        // org.glassfish.rmic.tools.java.Imports#resolve().

        exemptPackages = new HashSet<>(101);

        // Add all of the current packages and their prefixes to our set.
        for (ClassDeclaration c : getGeneratedClasses()) {
            SourceClass def = (SourceClass) c.getClassDefinition();
            if (def.isLocal())
                continue;

            Identifier pkg = def.getImports().getCurrentPackage();

            // Add the name of this package and all of its prefixes
            // to our set.
            while (pkg != idNull && exemptPackages.add(pkg)) {
                pkg = pkg.getQualifier();
            }
        }

        // Before we go any further, we make sure java.lang is
        // accessible and that it is not ambiguous.  These checks
        // are performed for "ordinary" packages in
        // org.glassfish.rmic.tools.java.Imports#resolve().  The reason we perform
        // them specially for java.lang is that we want to report
        // the error once, and outside of any particular file.

        // Check to see if java.lang is accessible.
        if (!exemptPackages.contains(idJavaLang)) {
            // Add java.lang to the set of exempt packages.
            exemptPackages.add(idJavaLang);

            if (!getPackage(idJavaLang).exists()) {
                // java.lang doesn't exist.
                error(0, "package.not.found.strong", idJavaLang);
            }
        }

        // Next we ensure that java.lang is not both a class and
        // a package.  (Fix for 4101529)
        //
        // This change has been backed out because, on WIN32, it
        // failed to take character case into account.  It will
        // be put back in later.
        //
        // Identifier resolvedName =
        //   resolvePackageQualifiedName(idJavaLang);
        // Identifier topClassName = resolvedName.getTopName();
        //     //if (Imports.importable(topClassName, env)) {
        // if (Imports.importable(topClassName, this)) {
        //    // It is a package and a class.  Emit the error.
        //    error(0, "package.class.conflict.strong",
        //            idJavaLang, topClassName);
        //    return;
        // }
    }

    /**
     * Get a class, given the fully qualified class name
     */
    public ClassDeclaration getClassDeclaration(Identifier nm) {
        return getClassDeclaration(Type.tClass(nm));
    }

    public ClassDeclaration getClassDeclaration(Type t) {
        ClassDeclaration c = classes.get(t);
        if (c == null) {
            classes.put(t, c = new ClassDeclaration(t.getClassName()));
            classesOrdered.addElement(c);
        }
        return c;
    }

    /**
     * Check if a class exists
     * Applies only to package members (non-nested classes).
     */
    public boolean classExists(Identifier nm) {
        if (nm.isInner()) {
            nm = nm.getTopName();       // just in case
        }
        Type t = Type.tClass(nm);
        ClassDeclaration c = classes.get(t);
        return (c != null) ? c.getName().equals(nm) : getPackage(nm.getQualifier()).classExists(nm.getName());
    }
    
    /**
     * Get the package path for a package
     */
    public Package getPackage(Identifier pkg) {
        Package p = packages.get(pkg);
        if (p == null) {
            packages.put(pkg, p = new Package(binaryPath, pkg));
        }
        return p;
    }

    /**
     * Parse a source file
     */
    public void parseFile(ClassFile file) throws FileNotFoundException {
        long tm = System.currentTimeMillis();
        InputStream input;
        BatchParser p;

        if (tracing) dtEnter("parseFile: PARSING SOURCE " + file);

        Environment env = new Environment(this, file);

        try {
            input = file.getInputStream();
            env.setCharacterEncoding(getCharacterEncoding());
            //      p = new BatchParser(e, new BufferedInputStream(input));
            p = new BatchParser(env, input);
        } catch(IOException ex) {
            if (tracing) dtEvent("parseFile: IO EXCEPTION " + file);
            throw new FileNotFoundException();
        }

        try {
            p.parseFile();
        } catch(Exception e) {
            throw new CompilerError(e);
        }

        try {
            input.close();
        } catch (IOException ex) {
            // We're turn with the input, so ignore this.
        }

        if (verbose()) {
            tm = System.currentTimeMillis() - tm;
            output(Main.getText("benv.parsed_in", file.getPath(),
                                Long.toString(tm)));
        }

        if (p.classes.size() == 0) {
            // The JLS allows a file to contain no compilation units --
            // that is, it allows a file to contain no classes or interfaces.
            // In this case, we are still responsible for checking that the
            // imports resolve properly.  The way the compiler is organized,
            // this is the last point at which we still have enough information
            // to do so. (Fix for 4041851).
            p.imports.resolve(env);
        } else {
            // In an attempt to see that classes which come from the
            // same source file are all recompiled when any one of them
            // would be recompiled (when using the -depend option) we
            // introduce artificial dependencies between these classes.
            // We do this by calling the addDependency() method, which
            // adds a (potentially unused) class reference to the constant
            // pool of the class.
            //
            // Previously, we added a dependency from every class in the
            // file, to every class in the file.  This introduced, in
            // total, a quadratic number of potentially bogus constant
            // pool entries.  This was bad.  Now we add our artificial
            // dependencies in such a way that the classes are connected
            // in a circle.  While single links is probably sufficient, the
            // code below adds double links just to be diligent.
            // (Fix for 4108286).
            //
            // Note that we don't chain in inner classes.  The links
            // between them and their outerclass should be sufficient
            // here.
            // (Fix for 4107960).
            //
            // The dependency code was previously in BatchParser.java.
            Enumeration<SourceClass> e = p.classes.elements();

            // first will not be an inner class.
            ClassDefinition first = e.nextElement();
            if (first.isInnerClass()) {
                throw new CompilerError("BatchEnvironment, first is inner");
            }

            ClassDefinition current = first;
            ClassDefinition next;
            while (e.hasMoreElements()) {
                next = e.nextElement();
                // Don't chain in inner classes.
                if (next.isInnerClass()) {
                    continue;
                }
                current.addDependency(next.getClassDeclaration());
                next.addDependency(current.getClassDeclaration());
                current = next;
            }
            // Make a circle.  Don't bother to add a dependency if there
            // is only one class in the file.
            if (current != first) {
                current.addDependency(first.getClassDeclaration());
                first.addDependency(current.getClassDeclaration());
            }
        }

        if (tracing) dtExit("parseFile: SOURCE PARSED " + file);
    }

    /**
     * Load a binary file
     */
    private ClassDefinition loadFile(ClassFile file) throws IOException {
        long tm = System.currentTimeMillis();
        InputStream input = file.getInputStream();
        ClassDefinition c;

        if (tracing) dtEnter("loadFile: LOADING CLASSFILE " + file);

        try {
            c = classDefinitionFactory.loadDefinition(input, new Environment(this, file));
        } catch (ClassFormatError e) {
            error(0, "class.format", file.getPath(), e.getMessage());
            if (tracing) dtExit("loadFile: CLASS FORMAT ERROR " + file);
            return null;
        } catch (java.io.EOFException e) {
            // If we get an EOF while processing a class file, then
            // it has been truncated.  We let other I/O errors pass
            // through.  Fix for 4088443.
            error(0, "truncated.class", file.getPath());
            return null;
        }

        input.close();
        if (verbose()) {
            tm = System.currentTimeMillis() - tm;
            output(Main.getText("benv.loaded_in", file.getPath(),
                                Long.toString(tm)));
        }

        if (tracing) dtExit("loadFile: CLASSFILE LOADED " + file);

        return c;
    }

    /**
     * Load a binary class
     */
    private boolean needsCompilation(Hashtable<ClassDeclaration, ClassDeclaration> check, ClassDeclaration c) {
        switch (c.getStatus()) {

          case CS_UNDEFINED:
            if (tracing) dtEnter("needsCompilation: UNDEFINED " + c.getName());
            loadDefinition(c);
            return needsCompilation(check, c);

          case CS_UNDECIDED:
            if (tracing) dtEnter("needsCompilation: UNDECIDED " + c.getName());
            if (check.get(c) == null) {
                check.put(c, c);

                ClassDefinition def = c.getClassDefinition();
                for (Iterator<ClassDeclaration> e = def.getDependencies(); e.hasNext() ;) {
                    ClassDeclaration dep = e.next();
                    if (needsCompilation(check, dep)) {
                        // It must be source, dependencies need compilation
                        c.setDefinition(def, CS_SOURCE);
                        if (tracing) dtExit("needsCompilation: YES (source) " + c.getName());
                        return true;
                    }
                }
            }
            if (tracing) dtExit("needsCompilation: NO (undecided) " + c.getName());
            return false;

          case CS_BINARY:
            if (tracing) {
                dtEnter("needsCompilation: BINARY " + c.getName());
                dtExit("needsCompilation: NO (binary) " + c.getName());
            }
            return false;

        }

        if (tracing) dtExit("needsCompilation: YES " + c.getName());
        return true;
    }

    /**
     * Load the definition of a class
     * or at least determine how to load it.
     * The caller must repeat calls to this method
     * until it the state converges to CS_BINARY, CS_PARSED, or the like..
     * @see ClassDeclaration#getClassDefinition
     */
    public void loadDefinition(ClassDeclaration c) {
        if (tracing) dtEnter("loadDefinition: ENTER " +
                             c.getName() + ", status " + c.getStatus());
        switch (c.getStatus()) {
          case CS_UNDEFINED: {
            if (tracing)
                dtEvent("loadDefinition: STATUS IS UNDEFINED");
            Identifier nm = c.getName();
            Package pkg;
              pkg = getPackage(nm.getQualifier());
              ClassFile binfile = pkg.getBinaryFile(nm.getName());
            if (binfile == null) {
                // must be source, there is no binary
                c.setDefinition(null, CS_SOURCE);
                if (tracing)
                    dtExit("loadDefinition: MUST BE SOURCE (no binary) " +
                           c.getName());
                return;
            }

            ClassFile srcfile = pkg.getSourceFile(nm.getName());
            if (srcfile == null) {
                if (tracing)
                    dtEvent("loadDefinition: NO SOURCE " + c.getName());
                ClassDefinition cDef;
                try {
                    cDef = loadFile(binfile);
                } catch (IOException e) {
                    // If we can't access the binary, set the class to
                    // be not found.  (bug id 4030497)
                    c.setDefinition(null, CS_NOTFOUND);

                    error(0, "io.exception", binfile);
                    if (tracing)
                        dtExit("loadDefinition: IO EXCEPTION (binary)");
                    return;
                }
                if ((cDef != null) && !cDef.getName().equals(nm)) {
                    error(0, "wrong.class", binfile.getPath(), c, cDef);
                    cDef = null;
                    if (tracing)
                        dtEvent("loadDefinition: WRONG CLASS (binary)");
                }
                if (cDef == null) {
                    // no source nor binary found
                    c.setDefinition(null, CS_NOTFOUND);
                    if (tracing)
                        dtExit("loadDefinition: NOT FOUND (source or binary)");
                    return;
                }

                // Couldn't find the source, try the one mentioned in the binary
                if (cDef.getSource() != null) {
                    srcfile = ClassFile.newClassFile(new File((String)cDef.getSource()));
                    // Look for the source file
                    srcfile = pkg.getSourceFile(srcfile.getName());
                    if ((srcfile != null) && srcfile.exists()) {
                        if (tracing)
                            dtEvent("loadDefinition: FILENAME IN BINARY " +
                                    srcfile);
                        if (srcfile.lastModified() > binfile.lastModified()) {
                            // must be source, it is newer than the binary
                            c.setDefinition(cDef, CS_SOURCE);
                            if (tracing)
                                dtEvent("loadDefinition: SOURCE IS NEWER " +
                                        srcfile);
                            cDef.loadNested(this);
                            if (tracing)
                                dtExit("loadDefinition: MUST BE SOURCE " +
                                       c.getName());
                            return;
                        }
                        if (dependencies()) {
                            c.setDefinition(cDef, CS_UNDECIDED);
                            if (tracing)
                                dtEvent("loadDefinition: UNDECIDED " +
                                        c.getName());
                        } else {
                            c.setDefinition(cDef, CS_BINARY);
                            if (tracing)
                                dtEvent("loadDefinition: MUST BE BINARY " +
                                        c.getName());
                        }
                        cDef.loadNested(this);
                        if (tracing)
                            dtExit("loadDefinition: EXIT " +
                                   c.getName() + ", status " + c.getStatus());
                        return;
                    }
                }

                // It must be binary, there is no source
                c.setDefinition(cDef, CS_BINARY);
                if (tracing)
                    dtEvent("loadDefinition: MUST BE BINARY (no source) " +
                                     c.getName());
                cDef.loadNested(this);
                if (tracing)
                    dtExit("loadDefinition: EXIT " +
                           c.getName() + ", status " + c.getStatus());
                return;
            }
            ClassDefinition cDef = null;
            try {
                if (srcfile.lastModified() > binfile.lastModified()) {
                    // must be source, it is newer than the binary
                    c.setDefinition(null, CS_SOURCE);
                    if (tracing)
                        dtEvent("loadDefinition: MUST BE SOURCE (younger than binary) " +
                                c.getName());
                    return;
                }
                cDef = loadFile(binfile);
            } catch (IOException e) {
                error(0, "io.exception", binfile);
                if (tracing)
                    dtEvent("loadDefinition: IO EXCEPTION (binary)");
            }
            if ((cDef != null) && !cDef.getName().equals(nm)) {
                error(0, "wrong.class", binfile.getPath(), c, cDef);
                cDef = null;
                if (tracing)
                    dtEvent("loadDefinition: WRONG CLASS (binary)");
            }
            if (cDef != null) {
                Identifier name = cDef.getName();
                if (name.equals(c.getName())) {
                    if (dependencies()) {
                        c.setDefinition(cDef, CS_UNDECIDED);
                        if (tracing)
                            dtEvent("loadDefinition: UNDECIDED " + name);
                    } else {
                        c.setDefinition(cDef, CS_BINARY);
                        if (tracing)
                            dtEvent("loadDefinition: MUST BE BINARY " + name);
                    }
                } else {
                    c.setDefinition(null, CS_NOTFOUND);
                    if (tracing)
                        dtEvent("loadDefinition: NOT FOUND (source or binary)");
                    if (dependencies()) {
                        getClassDeclaration(name).setDefinition(cDef, CS_UNDECIDED);
                        if (tracing)
                            dtEvent("loadDefinition: UNDECIDED " + name);
                    } else {
                        getClassDeclaration(name).setDefinition(cDef, CS_BINARY);
                        if (tracing)
                            dtEvent("loadDefinition: MUST BE BINARY " + name);
                    }
                }
            } else {
                c.setDefinition(null, CS_NOTFOUND);
                if (tracing)
                    dtEvent("loadDefinition: NOT FOUND (source or binary)");
            }
            if (cDef != null && cDef == c.getClassDefinition())
                cDef.loadNested(this);
            if (tracing) dtExit("loadDefinition: EXIT " +
                                c.getName() + ", status " + c.getStatus());
            return;
          }

          case CS_UNDECIDED: {
            if (tracing) dtEvent("loadDefinition: STATUS IS UNDECIDED");
            Hashtable<ClassDeclaration, ClassDeclaration> tab = new Hashtable<>();
            if (!needsCompilation(tab, c)) {
                // All undecided classes that this class depends on must be binary
                for (Enumeration<ClassDeclaration> e = tab.keys() ; e.hasMoreElements() ; ) {
                    ClassDeclaration dep = e.nextElement();
                    if (dep.getStatus() == CS_UNDECIDED) {
                        // must be binary, dependencies need compilation
                        dep.setDefinition(dep.getClassDefinition(), CS_BINARY);
                        if (tracing)
                            dtEvent("loadDefinition: MUST BE BINARY " + dep);
                    }
                }
            }
            if (tracing) dtExit("loadDefinition: EXIT " +
                                c.getName() + ", status " + c.getStatus());
            return;
          }

          case CS_SOURCE: {
            if (tracing) dtEvent("loadDefinition: STATUS IS SOURCE");
            ClassFile srcfile;
            Package pkg;
            if (c.getClassDefinition() != null) {
                // Use the source file name from the binary class file
                pkg = getPackage(c.getName().getQualifier());
                srcfile = pkg.getSourceFile((String)c.getClassDefinition().getSource());
                if (srcfile == null) {
                    String fn = (String)c.getClassDefinition().getSource();
                    srcfile = ClassFile.newClassFile(new File(fn));
                }
            } else {
                // Get a source file name from the package
                Identifier nm = c.getName();
                pkg = getPackage(nm.getQualifier());
                srcfile = pkg.getSourceFile(nm.getName());
                if (srcfile == null) {
                    // not found, there is no source
                    c.setDefinition(null, CS_NOTFOUND);
                    if (tracing)
                        dtExit("loadDefinition: SOURCE NOT FOUND " +
                               c.getName() + ", status " + c.getStatus());
                    return;
                }
            }
            try {
                parseFile(srcfile);
            } catch (FileNotFoundException e) {
                error(0, "io.exception", srcfile);
                if (tracing) dtEvent("loadDefinition: IO EXCEPTION (source)");
            }
            if ((c.getClassDefinition() == null) || (c.getStatus() == CS_SOURCE)) {
                // not found after parsing the file
                error(0, "wrong.source", srcfile.getPath(), c, pkg);
                c.setDefinition(null, CS_NOTFOUND);
                if (tracing)
                    dtEvent("loadDefinition: WRONG CLASS (source) " +
                            c.getName());
            }
            if (tracing) dtExit("loadDefinition: EXIT " +
                                c.getName() + ", status " + c.getStatus());
            return;
          }
        }
        if (tracing) dtExit("loadDefinition: EXIT " +
                            c.getName() + ", status " + c.getStatus());
    }

    /**
     * Create a new class.
     */
    public ClassDefinition makeClassDefinition(Environment toplevelEnv,
                                               long where,
                                               IdentifierToken name,
                                               String doc, int modifiers,
                                               IdentifierToken superClass,
                                               IdentifierToken interfaces[],
                                               ClassDefinition outerClass) {

        Identifier nm = name.getName();
        long nmpos = name.getWhere();

        Identifier pkgNm;
        String mangledName = null;
        ClassDefinition localContextClass = null;

        // Provide name for a local class.  This used to be set after
        // the class was created, but it is needed for checking within
        // the class constructor.
        // NOTE: It seems that we could always provide the simple name,
        // and thereby avoid the test in 'ClassDefinition.getLocalName()'
        // for the definedness of the local name.  There, if the local
        // name is not set, a simple name is extracted from the result of
        // 'getName()'.  That name can potentially change, however, as
        // it is ultimately derived from 'ClassType.className', which is
        // set by 'Type.changeClassName'.  Better leave this alone...
        Identifier localName = null;

        if (nm.isQualified() || nm.isInner()) {
            pkgNm = nm;
        } else if ((modifiers & (M_LOCAL | M_ANONYMOUS)) != 0) {
            // Inaccessible class.  Create a name of the form
            // 'PackageMember.N$localName' or 'PackageMember.N'.
            // Note that the '.' will be converted later to a '$'.
            //   pkgNm = generateName(outerClass, nm);
            localContextClass = outerClass.getTopClass();
            // Always use the smallest number in generating the name that
            // renders the complete name unique within the top-level class.
            // This is required to make the names more predictable, as part
            // of a serialization-related workaround, and satisfies an obscure
            // requirement that the name of a local class be of the form
            // 'PackageMember$1$localName' when this name is unique.
            for (int i = 1 ; ; i++) {
                mangledName = i + (nm.equals(idNull) ? "" : SIG_INNERCLASS + nm);
                if (localContextClass.getLocalClass(mangledName) == null) {
                    break;
                }
            }
            Identifier outerNm = localContextClass.getName();
            pkgNm = Identifier.lookupInner(outerNm, Identifier.lookup(mangledName));
            //System.out.println("LOCAL CLASS: " + pkgNm + " IN " + localContextClass);
            if ((modifiers & M_ANONYMOUS) != 0) {
                localName = idNull;
            } else {
                // Local class has a locally-scoped name which is independent of pkgNm.
                localName = nm;
            }
        } else if (outerClass != null) {
            // Accessible inner class.  Qualify name with surrounding class name.
            pkgNm = Identifier.lookupInner(outerClass.getName(), nm);
        } else {
            pkgNm = nm;
        }

        // Find the class
        ClassDeclaration c = toplevelEnv.getClassDeclaration(pkgNm);

        // Make sure this is the first definition
        if (c.isDefined()) {
            toplevelEnv.error(nmpos, "class.multidef",
                              c.getName(), c.getClassDefinition().getSource());
            // Don't mess with the existing class declarations with same name
            c = new ClassDeclaration (pkgNm);
        }

        if (superClass == null && !pkgNm.equals(idJavaLangObject)) {
            superClass = new IdentifierToken(idJavaLangObject);
        }

        ClassDefinition sourceClass =
            new SourceClass(toplevelEnv, where, c, doc,
                            modifiers, superClass, interfaces,
                            (SourceClass) outerClass, localName);

        if (outerClass != null) {
            // It is a member of its enclosing class.
            outerClass.addMember(toplevelEnv, new SourceMember(sourceClass));
            // Record local (or anonymous) class in the class whose name will
            // serve as the prefix of the local class name.  This is necessary
            // so that the class may be retrieved from its name, which does not
            // fully represent the class nesting structure.
            // See 'ClassDefinition.getClassDefinition'.
            // This is part of a fix for bugid 4054523 and 4030421.
            if ((modifiers & (M_LOCAL | M_ANONYMOUS)) != 0) {
                localContextClass.addLocalClass(sourceClass, mangledName);
            }
        }

        // The local name of an anonymous or local class used to be set here
        // with a call to 'setLocalName'.  This has been moved to the constructor
        // for 'SourceClass', which now takes a 'localName' argument.

        return sourceClass;
    }

    /*
     * makeMemberDefinition method is left with rawtypes and with lint messages suppressed.
     * The addition of Generics to com.org.glassfish.rmic.tools.* has uncovered an inconsistency
     * in usage though tools still work correctly as long as this function is allowed to
     * function as is.
     */

    /**
     * Create a new field.
     */
    @SuppressWarnings({"rawtypes","unchecked"})
    public MemberDefinition makeMemberDefinition(Environment origEnv, long where,
                                               ClassDefinition clazz,
                                               String doc, int modifiers,
                                               Type type, Identifier name,
                                               IdentifierToken argNames[],
                                               IdentifierToken expIds[],
                                               Object value) {
        if (tracing) dtEvent("makeMemberDefinition: " + name + " IN " + clazz);
        Vector v = null;
        if (argNames != null) {
            v = new Vector(argNames.length);
            for (IdentifierToken argName : argNames) {
                v.addElement(argName);
            }
        }
        SourceMember f = new SourceMember(where, clazz, doc, modifiers,
                                        type, name, v, expIds, (Node)value);
        clazz.addMember(origEnv, f);
        return f;
    }

    /**
     * Release resources in classpath.
     */
    public void shutdown() {
        try {
            if (binaryPath != null) {
                binaryPath.close();
            }
        } catch (IOException ee) {
            output(Main.getText("benv.failed_to_close_class_path",
                                ee.toString()));
        }
        binaryPath = null;

        super.shutdown();
    }

    /**
     * Error String
     */
    public
    String errorString(String err, Object arg1, Object arg2, Object arg3) {
        String key;

        if(err.startsWith("warn."))
            key = "javac.err." + err.substring(5);
        else
            key = "javac.err." + err;

        return Main.getText(key,
                            arg1 != null ? arg1.toString() : null,
                            arg2 != null ? arg2.toString() : null,
                            arg3 != null ? arg3.toString() : null);
    }

    /**
     * The filename where the last errors have occurred
     */
    private String errorFileName;

    /**
     * List of outstanding error messages
     */
    private ErrorMessage errors;

    /**
     * Insert an error message in the list of outstanding error messages.
     * The list is sorted on input position and contains no duplicates.
     * The return value indicates whether or not the message was
     * actually inserted.
     *
     * The method flushErrors() used to check for duplicate error messages.
     * It would only detect duplicates if they were contiguous.  Removing
     * non-contiguous duplicate error messages is slightly less complicated
     * at insertion time, so the functionality was moved here.  This also
     * saves a miniscule number of allocations.
     */
    private boolean insertError(long where, String message) {
        //output("ERR = " + message);

        if (errors == null
            ||  errors.where > where) {
            // If the list is empty, or the error comes before any other
            // errors, insert it at the beginning of the list.
            ErrorMessage newMsg = new ErrorMessage(where, message);
            newMsg.next = errors;
            errors = newMsg;

        } else if (errors.where == where
                   && errors.message.equals(message)) {
            // The new message is an exact duplicate of the first message
            // in the list.  Don't insert it.
            return false;

        } else {
            // Okay, we know that the error doesn't come first.  Walk
            // the list until we find the right position for insertion.
            ErrorMessage current = errors;
            ErrorMessage next;

            while ((next = current.next) != null
                   && next.where < where) {
                current = next;
            }

            // Now walk over any errors with the same location, looking
            // for duplicates.  If we find a duplicate, don't insert the
            // error.
            while ((next = current.next) != null
                   && next.where == where) {
                if (next.message.equals(message)) {
                    // We have found an exact duplicate.  Don't bother to
                    // insert the error.
                    return false;
                }
                current = next;
            }

            // Now insert after current.
            ErrorMessage newMsg = new ErrorMessage(where, message);
            newMsg.next = current.next;
            current.next = newMsg;
        }

        // Indicate that the insertion occurred.
        return true;
    }

    private int errorsPushed;

    /**
     * Maximum number of errors to print.
     */
    private int errorLimit = 100;

    private boolean hitErrorLimit;

    /**
     * Flush outstanding errors
     */

        public void pushError(String errorFileName, int line, String message,
                                    String referenceText, String referenceTextPointer) {
                int limit = errorLimit + nwarnings;
                if (++errorsPushed >= limit && errorLimit >= 0) {
                    if (!hitErrorLimit) {
                        hitErrorLimit = true;
                        output(errorString("too.many.errors",
                                           errorLimit,null,null));
                    }
                    return;
                }
                if (errorFileName.endsWith(".java")) {
                    output(errorFileName + ":" + line + ": " + message);
                    output(referenceText);
                    output(referenceTextPointer);
                } else {
                    // It wasn't really a source file (probably an error or
                    // warning because of a malformed or badly versioned
                    // class file.
                    output(errorFileName + ": " + message);
                }
        }

    public void flushErrors() {
        if (errors == null) {
            return;
        }

        boolean inputAvail = false;
        // Read the file
        char data[] = null;
        int dataLength = 0;
        // A malformed file encoding could cause a CharConversionException.
        // If something bad happens while trying to find the source file,
        // don't bother trying to show lines.
        try {
            FileInputStream in = new FileInputStream(errorFileName);
            data = new char[in.available()];
            InputStreamReader reader =
                (getCharacterEncoding() != null ?
                 new InputStreamReader(in, getCharacterEncoding()) :
                 new InputStreamReader(in));
            dataLength = reader.read(data);
            reader.close();
            inputAvail = true;
        } catch(IOException e) {
            // inputAvail will not be set
        }

        // Report the errors
        for (ErrorMessage msg = errors ; msg != null ; msg = msg.next) {
            // There used to be code here which checked
            // for duplicate error messages.  This functionality
            // has been moved to the method insertError().  See
            // the comments on that method for more information.

            int ln = (int) (msg.where >>> WHEREOFFSETBITS);
            int off = (int) (msg.where & ((1L << WHEREOFFSETBITS) - 1));
            if (off > dataLength)  off = dataLength;

            String referenceString = "";
            String markerString = "";
            if(inputAvail) {
                int i, j;
                for (i = off ; (i > 0) && (data[i - 1] != '\n') && (data[i - 1] != '\r') ; i--);
                for (j = off ; (j < dataLength) && (data[j] != '\n') && (data[j] != '\r') ; j++);
                referenceString = new String(data, i, j - i);

                char strdata[] = new char[(off - i) + 1];
                for (j = i ; j < off ; j++) {
                    strdata[j-i] = (data[j] == '\t') ? '\t' : ' ';
                }
                strdata[off-i] = '^';
                markerString = new String(strdata);
            }

            errorConsumer.pushError(errorFileName, ln, msg.message,
                                        referenceString, markerString);
        }
        errors = null;
    }

    /**
     * Report error
     */
    private void reportError(Object src, long where, String err, String msg) {
        if (src == null) {
            if (errorFileName != null) {
                flushErrors();
                errorFileName = null;
            }
            if (err.startsWith("warn.")) {
                if (warnings()) {
                    nwarnings++;
                    output(msg);
                }
                return;
            }
            output("error: " + msg);
            nerrors++;
            flags |= F_ERRORSREPORTED;

        } else if (src instanceof String) {
            String fileName = (String)src;

            // Flush errors if we've moved on to a new file.
            if (!fileName.equals(errorFileName)) {
                flushErrors();
                errorFileName = fileName;
            }

            // Classify `err' as a warning, deprecation warning, or
            // error message.  Proceed accordingly.
            if (err.startsWith("warn.")) {
                if (err.contains("is.deprecated")) {
                    // This is a deprecation warning.  Add `src' to the
                    // list of files with deprecation warnings.
                    if (!deprecationFiles.contains(src)) {
                        deprecationFiles.addElement(src);
                    }

                    // If we are reporting deprecations, try to add it
                    // to our list.  Otherwise, just increment the
                    // deprecation count.
                    if (deprecation()) {
                        if (insertError(where, msg)) {
                            ndeprecations++;
                        }
                    } else {
                        ndeprecations++;
                    }
                } else {
                    // This is a regular warning.  If we are reporting
                    // warnings, try to add it to the list.  Otherwise, just
                    // increment the warning count.
                    if (warnings()) {
                        if (insertError(where, msg)) {
                            nwarnings++;
                        }
                    } else {
                        nwarnings++;
                    }
                }
            } else {
                // This is an error.  Try to add it to the list of errors.
                // If it isn't a duplicate, increment our error count.
                if (insertError(where, msg)) {
                    nerrors++;
                    flags |= F_ERRORSREPORTED;
                }
            }
        } else if (src instanceof ClassFile) {
            reportError(((ClassFile)src).getPath(), where, err, msg);

        } else if (src instanceof Identifier) {
            reportError(src.toString(), where, err, msg);

        } else if (src instanceof ClassDeclaration) {
            try {
                reportError(((ClassDeclaration)src).getClassDefinition(this), where, err, msg);
            } catch (ClassNotFound e) {
                reportError(((ClassDeclaration)src).getName(), where, err, msg);
            }
        } else if (src instanceof ClassDefinition) {
            ClassDefinition c = (ClassDefinition)src;
            if (!err.startsWith("warn.")) {
                c.setError();
            }
            reportError(c.getSource(), where, err, msg);

        } else if (src instanceof MemberDefinition) {
            reportError(((MemberDefinition)src).getClassDeclaration(), where, err, msg);

        } else {
            output(src + ":error=" + err + ":" + msg);
        }
    }

    /**
     * Issue an error
     */
    public void error(Object source, long where, String err, Object arg1, Object arg2, Object arg3) {
        if (errorsPushed >= errorLimit + nwarnings) {
            // Don't bother to queue any more errors if they won't get printed.
            return;
        }
        if (System.getProperty("javac.dump.stack") != null) {
            output("javac.err."+err+": "+errorString(err, arg1, arg2, arg3));
            new Exception("Stack trace").printStackTrace(new PrintStream(out));
        }
        reportError(source, where, err, errorString(err, arg1, arg2, arg3));
    }

    /**
     * Output a string. This can either be an error message or something
     * for debugging.
     */
    public void output(String msg) {
        PrintStream out =
            this.out instanceof PrintStream ? (PrintStream)this.out
                                            : new PrintStream(this.out, true);
        out.println(msg);
    }

}
