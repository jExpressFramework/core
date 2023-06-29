/*
 * Copyright 2005-2022 Du Law Office - The Summer Boot Framework Project
 *
 * The Summer Boot Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License and you have no
 * policy prohibiting employee contributions back to this file (unless the contributor to this
 * file is your current or retired employee). You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.summerboot.jexpress.boot;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.netty.channel.ChannelHandler;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.summerboot.jexpress.boot.annotation.Controller;
import org.summerboot.jexpress.boot.annotation.Unique;
import org.summerboot.jexpress.boot.annotation.Version;
import org.summerboot.jexpress.boot.config.JExpressConfig;
import org.summerboot.jexpress.boot.config.annotation.ImportResource;
import org.summerboot.jexpress.security.auth.AuthConfig;
import org.summerboot.jexpress.util.ApplicationUtil;
import org.summerboot.jexpress.util.BeanUtil;
import org.summerboot.jexpress.util.ReflectionUtil;
import org.summerboot.jexpress.boot.annotation.Service;
import org.summerboot.jexpress.boot.annotation.GrpcService;
import org.summerboot.jexpress.boot.annotation.Service.ChannelHandlerType;
import org.summerboot.jexpress.i18n.I18n;

/**
 * In Code We Trust
 *
 * @author Changski Tie Zheng Zhang 张铁铮, 魏泽北, 杜旺财, 杜富贵
 */
abstract public class SummerSingularity implements BootConstant {

    protected static Logger log;
    protected static final String CLI_CONFIG_DOMAIN = "domain";
    protected static final String CLI_CONFIG_DIR = "cfgdir";
    protected static final String DEFAULT_CFG_DIR_NAME = "configuration";
    protected static final File DEFAULT_CFG_DIR = new File(DEFAULT_CFG_DIR_NAME).getAbsoluteFile();
    protected static final File CURRENT_DIR = new File("").getAbsoluteFile();
    protected File userSpecifiedConfigDir;
    protected File pluginDir;

    protected final StringBuilder memo = new StringBuilder();
    protected final Class primaryClass;

    /*
     * Scan Results
     */
    protected String jvmStartCommand;
    protected boolean jmxRequired;
    protected String callerRootPackageName;//also used by JPAHibernateConfig access to scan @Entity
    protected String appVersion = BootConstant.VERSION;
    protected String logFileName = BootConstant.VERSION;

    /*
     * Annotation scan results as CLI inputs
     */
    protected final List<String> availableUniqueTagOptions = new ArrayList();
    protected final Map<String, ConfigMetadata> scanedJExpressConfigs = new LinkedHashMap<>();
    protected final Set<String> availableImplTagOptions = new HashSet();
    protected final Set<Class<? extends BindableService>> gRPCBindableServiceImplClasses = new HashSet();
    protected final Set<Class<ServerServiceDefinition>> gRPCServerServiceDefinitionImplClasses = new HashSet();
    protected boolean hasControllers = false;
    protected boolean hasGRPCImpl = false;
    protected boolean hasAuthImpl = false;

    /*
     * Annotation scan results as BootGuiceModule input
     * Format: bindingClass <--> {key=(ImplTag+named) <--> [@Service impl classes list]}
     */
    protected final Map<Class, Map<String, List<ServiceMetadata>>> scanedServiceBindingMap = new HashMap();
    protected final Map<Service.ChannelHandlerType, Set<String>> channelHandlerNames = new HashMap();

    protected SummerSingularity(Class callerClass, String... args) {
        System.out.println("SummerApplication loading from " + BootConstant.HOST);
        primaryClass = callerClass == null
                ? this.getClass()
                : callerClass;
        singularity();
        bigBang(args);
    }

    private void singularity() {
        SummerApplication.SystemErrorCodeAsInt = false;
        memo.setLength(0);
        userSpecifiedConfigDir = null;
        pluginDir = null;
        System.getProperties().remove(LOG4J2_KEY);
        System.getProperties().remove(LOG4J2_JDKADAPTER_KEY);
        System.getProperties().remove(SYS_PROP_APP_PACKAGE_NAME, "");// used by log4j2.xml
        System.getProperties().remove(SYS_PROP_LOGFILENAME, "");// used by log4j2.xml
        System.getProperties().remove(SYS_PROP_APP_VERSION, "");// used by BootController.version()   
        System.setProperty(BootConstant.LOG4J2_KEY, "");
        // reset Scan Results
        jvmStartCommand = null;
        jmxRequired = false;
        callerRootPackageName = null;
        appVersion = BootConstant.VERSION;
        logFileName = BootConstant.VERSION;

        //reset Annotation scan results as CLI inputs
        availableUniqueTagOptions.clear();
        scanedJExpressConfigs.clear();
        availableImplTagOptions.clear();
        gRPCBindableServiceImplClasses.clear();
        gRPCServerServiceDefinitionImplClasses.clear();
        hasControllers = false;
        hasGRPCImpl = false;
        hasAuthImpl = false;
    }

    private <T extends SummerApplication> T bigBang(String[] args) {
        memo.append("\n\t- deployee callerClass=").append(primaryClass.getName());
        callerRootPackageName = ReflectionUtil.getRootPackageName(primaryClass);
        memo.append("\n\t- callerRootPackageName=").append(callerRootPackageName);
        StringBuilder sb = new StringBuilder();
        jmxRequired = ApplicationUtil.scanJVM_StartCommand(sb);
        jvmStartCommand = sb.toString();

        scanAnnotation_Version(primaryClass);
        System.setProperty(SYS_PROP_APP_PACKAGE_NAME, callerRootPackageName);// used by log4j2.xml
        System.setProperty(SYS_PROP_LOGFILENAME, logFileName);// used by log4j2.xml as log file name
        System.setProperty(SYS_PROP_APP_VERSION, appVersion);// used by BootController.version()    
        scanArgsToInitializePluginFromConfigDir(args);
        log = LogManager.getLogger(SummerApplication.class);// init log
        log.debug("Configuration path = {}", userSpecifiedConfigDir);
        /*
         * load external modules
         */
        try {
            scanPluginJars(pluginDir, true);// depends -domain or -cfgdir
        } catch (IOException ex) {
            System.out.println(ex + "\n\tFailed to load plugin jar files from " + pluginDir);
            ex.printStackTrace();
            System.exit(1);
        }

        String error = scanAnnotation_Unique(callerRootPackageName, memo);
        if (error != null) {
            System.out.println(error);
            System.exit(1);
        }
        scanAnnotation_JExpressConfigImportResource("org.summerboot.jexpress", callerRootPackageName);
        scanImplementation_gRPC(callerRootPackageName);
        scanAnnotation_Controller(callerRootPackageName);
        scanAnnotation_Service(callerRootPackageName);
        scanAnnotation_DeclareRoles(callerRootPackageName);
        return (T) this;
    }

    protected void scanAnnotation_Version(Class callerClass) {
        Version version = (Version) callerClass.getAnnotation(Version.class);
        if (version != null) {
            String logManager = version.LogManager();
            if (StringUtils.isNotBlank(logManager)) {
                System.setProperty(LOG4J2_JDKADAPTER_KEY, logManager);// https://logging.apache.org/log4j/log4j-2.3.2/log4j-jul/index.html
            }
            SummerApplication.SystemErrorCodeAsInt = version.SystemErrorCodeAsInt();
            logFileName = version.logFileName();
            if (StringUtils.isBlank(logFileName)) {
                logFileName = version.value()[0];
            }
            appVersion = version.value()[0];
            System.setProperty(SYS_PROP_APP_VERSION_SHORT, appVersion);
            int versionCount = version.value().length;
            if (versionCount > 1) {
                appVersion = appVersion + " (";
                for (int i = 1; i < versionCount; i++) {
                    appVersion = appVersion + version.value()[i] + " ";
                }
                appVersion = appVersion + ")";
            }
        } else {
            logFileName = "app";
        }
        memo.append("\n\t- callerVersion=").append(appVersion);
    }

    protected void scanArgsToInitializePluginFromConfigDir(String[] args) {
        /*
         * [Config File] Location - determine the configuration path: userSpecifiedConfigDir
         */
        userSpecifiedConfigDir = null;//to clear the state
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String cli = args[i];
                if (("-" + CLI_CONFIG_DIR).equals(cli)) {
                    String cfgDir = args[++i];
                    userSpecifiedConfigDir = new File(cfgDir).getAbsoluteFile();
                    break;
                } else if (("-" + CLI_CONFIG_DOMAIN).equals(cli)) {
                    String envTag = args[++i];
                    String cfgDir = /*unittestWorkingDir +*/ "standalone_" + envTag + File.separator + DEFAULT_CFG_DIR_NAME;
                    userSpecifiedConfigDir = new File(cfgDir).getAbsoluteFile();
                    System.setProperty("domainName", envTag);
                    break;
                }
            }
        }

        if (userSpecifiedConfigDir == null) {
            userSpecifiedConfigDir = DEFAULT_CFG_DIR;
        }
        if (!userSpecifiedConfigDir.exists()) {
            userSpecifiedConfigDir.mkdirs();
        }

//        if (userSpecifiedConfigDir.getAbsolutePath().equals(CURRENT_DIR.getAbsolutePath())) {
//            //set log folder inside user specified config folder
//            System.setProperty(SYS_PROP_LOGGINGPATH, userSpecifiedConfigDir.getAbsolutePath());//used by log4j2.xml
//            pluginDir = new File(userSpecifiedConfigDir.getAbsolutePath(), BootConstant.DIR_PLUGIN).getAbsoluteFile();
//        } else {
        if (!userSpecifiedConfigDir.exists() || !userSpecifiedConfigDir.isDirectory() || !userSpecifiedConfigDir.canRead()) {
            System.out.println("Could access configuration path as a folder: " + userSpecifiedConfigDir);
            System.exit(1);
        }
        //set log folder outside user specified config folder
        System.setProperty(SYS_PROP_LOGGINGPATH, userSpecifiedConfigDir.getParent());//used by log4j2.xml
        pluginDir = new File(userSpecifiedConfigDir.getParentFile(), BootConstant.DIR_PLUGIN).getAbsoluteFile();
//        }

        /*
         * [Config File] Log4J - init
         */
        String location = userSpecifiedConfigDir.getAbsolutePath();
        ClassLoader classLoader = this.getClass().getClassLoader();
        Path logFilePath = ApplicationUtil.createIfNotExist(location, classLoader, "log4j2.xml.temp", "log4j2.xml");
        String log4j2ConfigFile = logFilePath.toString();
        System.setProperty(BootConstant.LOG4J2_KEY, log4j2ConfigFile);
        Locale userSpecifiedResourceBundle = null;
        memo.append("\n\t- ").append(I18n.info.launchingLog.format(userSpecifiedResourceBundle, System.getProperty(BootConstant.LOG4J2_KEY)));

    }

    protected void scanPluginJars(File pluginDir, boolean failOnUndefinedClasses) throws IOException {
        pluginDir.mkdirs();
        if (!pluginDir.canRead() || !pluginDir.isDirectory()) {
            memo.append("\n\t- loadPluginJars: invalid dir ").append(pluginDir);
            return;
        }
        FileFilter fileFilter = file -> !file.isDirectory() && file.getName().endsWith(".jar");
        File[] jarFiles = pluginDir.listFiles(fileFilter);
        if (jarFiles == null || jarFiles.length < 1) {
            memo.append("\n\t- loadPluginJars: no jar files found at ").append(pluginDir);
            return;
        }
        Set<Class<?>> pluginClasses = new HashSet<>();
        for (File jarFile : jarFiles) {
            memo.append("\n\t- loadPluginJars: loading jar file ").append(jarFile.getAbsolutePath());
            Set<Class<?>> classes = ApplicationUtil.loadClassFromJarFile(jarFile, failOnUndefinedClasses);
            memo.append("\n\t- loadPluginJars: loaded ").append(classes.size()).append(" classes from jar file ").append(jarFile.getAbsolutePath());
            pluginClasses.addAll(classes);
        }
        ReflectionUtil.setPluginClasses(pluginClasses);
        memo.append("\n\t- loadPluginJars: loaded ").append(pluginClasses.size()).append(" classes from ").append(jarFiles.length).append(" jar files in ").append(pluginDir);
    }

    /**
     *
     * @param rootPackageName
     * @param sb
     * @param displayByTags
     * @return error message
     */
    protected String scanAnnotation_Unique(String rootPackageName, StringBuilder sb, String... displayByTags) {
        Set<Class<?>> classes = ReflectionUtil.getAllImplementationsByAnnotation(Unique.class, rootPackageName, false);
        StringBuilder errors = new StringBuilder();
        boolean error = false;
        for (Class classWithUniqueValues : classes) {
            if (!classWithUniqueValues.isInterface()) {
                error = true;
                errors.append("\n\t @Unique can only apply on interfaces, ").append(classWithUniqueValues).append(" is not an interface");
                continue;
            }
            Unique u = (Unique) classWithUniqueValues.getAnnotation(Unique.class);
            String tag = u.name();
            availableUniqueTagOptions.add(tag);
            Class uniqueType = u.type();
            List<String> tags = List.of(displayByTags);
            try {
                Map<Object, Set<String>> duplicated = ApplicationUtil.checkDuplicateFields(classWithUniqueValues, uniqueType);
                if (!duplicated.isEmpty()) {
                    String report = BeanUtil.toJson(duplicated, true, false);
                    return "Duplicated " + uniqueType.getSimpleName() + " values in " + classWithUniqueValues.getSimpleName() + " " + report;
                } else if (tags.contains(tag)) {
                    Map<String, Integer> results = new HashMap();
                    ReflectionUtil.loadFields(classWithUniqueValues, uniqueType, results, false);
                    Map<Object, String> sorted = results
                            .entrySet()
                            .stream()
                            .sorted(Map.Entry.comparingByValue())
                            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (e1, e2) -> e1, LinkedHashMap::new));
                    String json = BeanUtil.toJson(sorted, true, false);
                    sb.append("\n").append(tag).append("=").append(json);
                }
            } catch (Throwable ex) {
                throw new RuntimeException("check unique failed on package " + rootPackageName + ".*", ex);
            }
        }
        if (error) {
            throw new RuntimeException(errors.toString());
        }
        return null;
    }

    protected void scanAnnotation_JExpressConfigImportResource(String... rootPackageNames) {
        Set<String> pakcages = Set.copyOf(List.of(rootPackageNames));
        Set<Class<? extends JExpressConfig>> classesAll = new HashSet();//to remove duplicated
        for (String rootPackageName : pakcages) {
            Set<Class<? extends JExpressConfig>> jExpressConfigClasses = ReflectionUtil.getAllImplementationsByInterface(JExpressConfig.class, rootPackageName);
            classesAll.addAll(jExpressConfigClasses);
        }

        for (Class jExpressConfigClass : classesAll) {
            int mod = jExpressConfigClass.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isInterface(mod)) {
                continue;
            }
            String key = jExpressConfigClass.getSimpleName();
            if (scanedJExpressConfigs.containsKey(key)) {
                continue;
            }
            String configFileName = null;
            ImportResource ir = (ImportResource) jExpressConfigClass.getAnnotation(ImportResource.class);
            if (ir != null) {
                configFileName = ir.value();
                String checkImplTagUsed = ir.checkImplTagUsed();
                boolean loadWhenImplTagUsed = ir.loadWhenImplTagUsed();

                ConfigMetadata metadata = new ConfigMetadata(configFileName, jExpressConfigClass, null, checkImplTagUsed, loadWhenImplTagUsed);
                //availableAppConfigs.add(rc);
                scanedJExpressConfigs.put(key, metadata);
                memo.append("\n\t- scan.JExpressConfig.ImportResource:").append(key).append("=").append(metadata);
            }
            memo.append("\n\t- cfg.scaned=").append(jExpressConfigClass.getName()).append(", file=").append(configFileName);
        }
    }

    protected void scanImplementation_gRPC(String... pakcages) {
        //gRPCBindableServiceImplClasses.addAll(ReflectionUtil.getAllImplementationsByInterface(BindableService.class, callerRootPackageName));
        for (String rootPackageName : pakcages) {
            Set<Class<?>> gRPCServerClasses = ReflectionUtil.getAllImplementationsByAnnotation(GrpcService.class, rootPackageName, false);
            for (Class gRPCServerClass : gRPCServerClasses) {
                if (BindableService.class.isAssignableFrom(gRPCServerClass)) {
                    gRPCBindableServiceImplClasses.add(gRPCServerClass);
                } else if (ServerServiceDefinition.class.equals(gRPCServerClass)) {
                    gRPCServerServiceDefinitionImplClasses.add(gRPCServerClass);
                }
            }
        }
        hasGRPCImpl = !gRPCServerServiceDefinitionImplClasses.isEmpty() || !gRPCBindableServiceImplClasses.isEmpty();
    }

    protected void scanAnnotation_Controller(String... rootPackageNames) {
        Set<Class<?>> classesAll = new HashSet();//to remove duplicated
        for (String rootPackageName : rootPackageNames) {
            Set<Class<?>> classes = ReflectionUtil.getAllImplementationsByAnnotation(Controller.class, rootPackageName, false);
            classesAll.addAll(classes);
        }
        List<String> tags = new ArrayList();
        for (Class c : classesAll) {
            Controller a = (Controller) c.getAnnotation(Controller.class);
            if (a == null) {
                continue;
            }
            String implTag = a.implTag();
            tags.add(implTag);
        }
        List<String> serviceImplTags = tags.stream()
                .distinct()
                .collect(Collectors.toList());
        serviceImplTags.removeAll(Collections.singleton(null));
        serviceImplTags.removeAll(Collections.singleton(""));
        serviceImplTags.removeAll(Collections.singleton(Controller.NOT_TAGGED));
        availableImplTagOptions.addAll(serviceImplTags);
    }

    protected List<String> scanAnnotation_Service(String... rootPackageNames) {
        Set<Class<?>> classesAll = new HashSet();//to remove duplicated
        for (String rootPackageName : rootPackageNames) {
            Set<Class<?>> classes = ReflectionUtil.getAllImplementationsByAnnotation(Service.class, rootPackageName, false);
            classesAll.addAll(classes);
        }

        return scanAnnotation_Service(classesAll);
    }

    protected List<String> scanAnnotation_Service(Set<Class<?>> classesAll) {
        List<String> tags = new ArrayList();
        StringBuilder sb = new StringBuilder();
        for (Class serviceImplClass : classesAll) {
            Service serviceAnnotation = (Service) serviceImplClass.getAnnotation(Service.class);
            if (serviceAnnotation == null) {
                continue;
            }
            String named = serviceAnnotation.named().trim();
            String implTag = serviceAnnotation.implTag().trim();
            tags.add(implTag);
            String uniqueKey = "named=" + named + ", implTag=" + implTag;
            Class[] bindingClasses = serviceAnnotation.binding();
            Service.ChannelHandlerType ChannelHandlerType = serviceAnnotation.type();
            if (bindingClasses != null && bindingClasses.length > 0) {//developer specified 
                for (Class bindingClass : bindingClasses) {
                    if (!bindingClass.isAssignableFrom(serviceImplClass)) {
                        List<Class> interfaces = ReflectionUtil.getAllInterfaces(serviceImplClass, true);
                        List<Class> superclasses = ReflectionUtil.getAllSuperClasses(serviceImplClass);
                        interfaces.addAll(superclasses);
                        interfaces.remove(Object.class);
                        sb.append("\n\t").append(serviceImplClass).append(" specifies @").append(Service.class.getSimpleName()).append("(binding=").append(bindingClass.getSimpleName()).append(".class), which is not in its Interfaces:").append(interfaces);
                        continue;
                    }
                    scanAnnotation_Service_Add2BindingMap(bindingClass, uniqueKey, new ServiceMetadata(serviceImplClass, named, implTag, ChannelHandlerType), sb);
                }
            } else {//bindingClass not specified by developer, use its declaired interfaces by default
                List<Class> declaredInterfaces = ReflectionUtil.getAllInterfaces(serviceImplClass, false);
                if (declaredInterfaces.isEmpty()) {
                    List<Class> superInterfaces = ReflectionUtil.getAllInterfaces(serviceImplClass.getSuperclass(), true);
                    if (superInterfaces.isEmpty()) {
                        sb.append("\n\t").append(serviceImplClass).append(" does not implement any interfaces.");
                    } /*else if (superInterfaces.size() == 1) {
                        Class bindingClass = superInterfaces.get(0);
                        scanAnnotation_Service_Add2BindingMap(bindingClass, uniqueKey, serviceImplClass, named);
                    } */ else {
                        sb.append("\n\t").append(serviceImplClass).append(" needs to specify the binding interface @").append(Service.class.getSimpleName()).append("(binding=TheMissingInterface.class), which implemented by supper class: ").append(superInterfaces);
                    }
                    continue;
                }
                for (Class bindingClass : declaredInterfaces) {
                    scanAnnotation_Service_Add2BindingMap(bindingClass, uniqueKey, new ServiceMetadata(serviceImplClass, named, implTag, ChannelHandlerType), sb);
                }
            }
        }
        scanAnnotation_Service_ValidateBindingMap(sb);
        //Java 17if (!sb.isEmpty()) {
        String error = sb.toString();
        if (!error.isBlank()) {
            System.out.println("IOC Code error:" + sb);
            System.exit(1);
        }
        List<String> serviceImplTags = tags.stream()
                .distinct()
                .collect(Collectors.toList());
        serviceImplTags.removeAll(Collections.singleton(null));
        serviceImplTags.removeAll(Collections.singleton(""));
        serviceImplTags.removeAll(Collections.singleton(Service.NOT_TAGGED));
        availableImplTagOptions.addAll(serviceImplTags);
        return serviceImplTags;
    }

    protected void scanAnnotation_Service_Add2BindingMap(Class bindingClass, String uniqueKey, ServiceMetadata service, StringBuilder sb) {
        memo.append("\n\t- scan.taggedservice.add to guiceModule.bind(").append(bindingClass.getName()).append(").to(").append(service).append("), uniqueKey=").append(uniqueKey);
        Map<String, List<ServiceMetadata>> taggeServicedMap = scanedServiceBindingMap.get(bindingClass);
        if (taggeServicedMap == null) {
            taggeServicedMap = new HashMap();
            scanedServiceBindingMap.put(bindingClass, taggeServicedMap);
        }
        List<ServiceMetadata> serviceImplList = taggeServicedMap.get(uniqueKey);
        if (serviceImplList == null) {
            serviceImplList = new ArrayList();
            taggeServicedMap.put(uniqueKey, serviceImplList);
        }
        if (bindingClass.equals(ChannelHandler.class)) {
            ChannelHandlerType channelHandlerType = service.getChannelHandlerType();
            if (channelHandlerType == null || channelHandlerType == ChannelHandlerType.nptspecified) {
                sb.append("\n\t").append(service.getServiceImplClass()).append(" needs to specify type @").append(Service.class.getSimpleName()).append("(binding=ChannelHandler.class, type=?), when binding=ChannelHandler.class");
            }
        }
        serviceImplList.add(service);
    }

    protected void scanAnnotation_Service_ValidateBindingMap(StringBuilder sb) {
        for (Class keyBindingClass : scanedServiceBindingMap.keySet()) {
            Map<String, List<ServiceMetadata>> taggeServicedMap = scanedServiceBindingMap.get(keyBindingClass);
            for (String keyImplTag : taggeServicedMap.keySet()) {
                List<ServiceMetadata> serviceImplList = taggeServicedMap.get(keyImplTag);
                int size = serviceImplList.size();
                if (size != 1) {
                    sb.append("\nIOC ").append(keyBindingClass).append(" required a single bean, but ").append(size).append(" were found with the same named+implTag(").append(keyImplTag).append("): ").append(serviceImplList);
                }
            }
        }
    }

    protected void scanAnnotation_DeclareRoles(String... rootPackageNames) {
        Set<String> declareRoles = new TreeSet();
        Set<Class<?>> classesAll = new HashSet();//to remove duplicated
        for (String rootPackageName : rootPackageNames) {
            Set<Class<?>> classes = ReflectionUtil.getAllImplementationsByAnnotation(Controller.class, rootPackageName, false);
            classesAll.addAll(classes);
        }
        hasControllers = !classesAll.isEmpty();
        // @DeclareRoles
        for (Class c : classesAll) {
            DeclareRoles drs = (DeclareRoles) c.getAnnotation(DeclareRoles.class);
            if (drs != null) {
                String[] roles = drs.value();
                declareRoles.addAll(Arrays.asList(roles));
            }
            // @RolesAllowed
            List<Method> methods = ReflectionUtil.getDeclaredAndSuperClassesMethods(c, true);
            for (Method javaMethod : methods) {
                RolesAllowed ra = javaMethod.getAnnotation(RolesAllowed.class);
                if (ra == null) {
                    continue;
                }
                String[] roles = ra.value();
                declareRoles.addAll(Arrays.asList(roles));
            }
        }
        final AuthConfig authCfg = AuthConfig.cfg;
        authCfg.addDeclareRoles(declareRoles);
        memo.append("\n\t- scan.DeclareRoles=").append(declareRoles);

        //2. check is there any declared roles so that the auth config should be used
        hasAuthImpl = !authCfg.getDeclareRoles().isEmpty();
    }

    protected static class ConfigMetadata {

        final Class cfgClass;
        final String configFileName;
        final JExpressConfig instance;
        final String checkImplTagUsed;
        final boolean loadWhenImplTagUsed;

        ConfigMetadata(String configFileName, Class cfgClass, JExpressConfig instance, String checkImplTagUsed, boolean loadWhenImplTagUsed) {
            this.configFileName = configFileName;
            this.cfgClass = cfgClass;
            this.instance = instance;
            this.checkImplTagUsed = checkImplTagUsed;
            this.loadWhenImplTagUsed = loadWhenImplTagUsed;
        }

        @Override
        public String toString() {
            return "ConfigMetadata{" + "cfgClass=" + cfgClass.getName() + ", configFileName=" + configFileName + ", instance=" + instance + ", checkImplTagUsed=" + checkImplTagUsed + ", loadWhenImplTagUsed=" + loadWhenImplTagUsed + '}';
        }
    }

    public static class ServiceMetadata {

        final Class serviceImplClass;
        final String named;
        final String implTag;
        final ChannelHandlerType channelHandlerType;

        public ServiceMetadata(Class serviceImplClass, String named, String implTag, ChannelHandlerType channelHandlerType) {
            this.serviceImplClass = serviceImplClass;
            this.named = named;
            this.implTag = implTag;
            this.channelHandlerType = channelHandlerType;
        }

        @Override
        public String toString() {
            return "ServiceImpl{" + serviceImplClass.getName() + ", named=" + named + ", implTag=" + implTag + '}';
        }

        public Class getServiceImplClass() {
            return serviceImplClass;
        }

        public String getNamed() {
            return named;
        }

        public String getImplTag() {
            return implTag;
        }

        public ChannelHandlerType getChannelHandlerType() {
            return channelHandlerType;
        }
    }
}
