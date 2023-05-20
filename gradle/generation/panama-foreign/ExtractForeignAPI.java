/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class ExtractForeignAPI {
  
  private static final FileTime FIXED_FILEDATE = FileTime.from(Instant.parse("2022-01-01T00:00:00Z"));
  
  static final Map<String,String> CLASSFILE_MATCHERS = Map.of(
      "java.base",             "glob:java/{lang/foreign/*,nio/channels/FileChannel}.class",
      "jdk.incubator.vector",  "glob:jdk/incubator/vector/*.class"
  );
  
  static final Map<Integer,List<String>> MODULES_TO_PROCESS = Map.of(
      19, List.of("java.base"),
      20, List.of("java.base", "jdk.incubator.vector")      
  );
  
  public static void main(String... args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("Need two parameters: java version, output file");
    }
    int jdk = Integer.parseInt(args[0]);
    if (jdk != Runtime.version().feature()) {
      throw new IllegalStateException("Incorrect java version: " + Runtime.version().feature());
    }
    var outputPath = Paths.get(args[1]);

    try (var out = new ZipOutputStream(Files.newOutputStream(outputPath))) {
      for (String mod : MODULES_TO_PROCESS.get(jdk)) {
        var modulePath = Paths.get(URI.create("jrt:/")).resolve(mod).toRealPath();
        var moduleMatcher = modulePath.getFileSystem().getPathMatcher(CLASSFILE_MATCHERS.get(mod));
        process(modulePath, moduleMatcher, out);
      }
    }
  }

  static void process(Path modulePath, PathMatcher fileMatcher, ZipOutputStream out) throws IOException {
    var classReaders = new ArrayList<ClassReader>();
    try (var stream = Files.walk(modulePath)) {
      var filesToExtract = stream.map(modulePath::relativize).filter(fileMatcher::matches).sorted().toArray(Path[]::new);
      System.out.println("Prescanning class files in [" + modulePath + "]...");
      for (Path relative : filesToExtract) {
        try (var in = Files.newInputStream(modulePath.resolve(relative))) {
          var reader = new ClassReader(in);
          classReaders.add(reader);
        }
      }
    }
    var visibleClasses = classReaders.stream().filter(r -> isVisible(r.getAccess()))
        .map(ClassReader::getClassName)
        .collect(Collectors.toUnmodifiableSet());
    var classesToInclude = new HashSet<String>(visibleClasses);
    var processed = new HashMap<String, byte[]>();
    System.out.println("Transforming class files in [" + modulePath + "]...");
    for (ClassReader reader : classReaders) {
      var cw = new ClassWriter(0);
      var cleaner = new Cleaner(cw, visibleClasses, classesToInclude);
      reader.accept(cleaner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      processed.put(reader.getClassName(), cw.toByteArray());
    }
    System.out.println("Writing visible class files for [" + modulePath + "]...");
    for (ClassReader reader : classReaders) {
      String cn = reader.getClassName();
      if (classesToInclude.contains(cn)) {
        System.out.println("Writing stub for class: " + cn);
        var bytes = processed.get(cn);
        out.putNextEntry(new ZipEntry(cn.concat(".class")).setLastModifiedTime(FIXED_FILEDATE));
        out.write(bytes);
        out.closeEntry();
      }
    }
  }
  
  static boolean isVisible(int access) {
    return (access & (Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC)) != 0;
  }
  
  static class Cleaner extends ClassVisitor {
    private static final String PREVIEW_ANN = "jdk/internal/javac/PreviewFeature";
    private static final String PREVIEW_ANN_DESCR = Type.getObjectType(PREVIEW_ANN).getDescriptor();
    
    private final Set<String> visibleClasses, classesToInclude;
    
    Cleaner(ClassWriter out, Set<String> visibleClasses, Set<String> classesToInclude) {
      super(Opcodes.ASM9, out);
      this.visibleClasses = visibleClasses;
      this.classesToInclude = classesToInclude;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      super.visit(Opcodes.V11, access, name, signature, superName, interfaces);
      //completelyHidden = !isVisible(access);
      classesToInclude.add(superName);
      classesToInclude.addAll(Arrays.asList(interfaces));
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      return Objects.equals(descriptor, PREVIEW_ANN_DESCR) ? null : super.visitAnnotation(descriptor, visible);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
      if (!isVisible(access)) {
        return null;
      }
      return new FieldVisitor(Opcodes.ASM9, super.visitField(access, name, descriptor, signature, value)) {
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
          return Objects.equals(descriptor, PREVIEW_ANN_DESCR) ? null : super.visitAnnotation(descriptor, visible);
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
      if (!isVisible(access)) {
        return null;
      }
      return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
          return Objects.equals(descriptor, PREVIEW_ANN_DESCR) ? null : super.visitAnnotation(descriptor, visible);
        }
      };
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      if (!Objects.equals(outerName, PREVIEW_ANN)) {
        super.visitInnerClass(name, outerName, innerName, access);
      }
    }
    
    @Override
    public void visitPermittedSubclass​(String c) {
    }

  }
  
}
