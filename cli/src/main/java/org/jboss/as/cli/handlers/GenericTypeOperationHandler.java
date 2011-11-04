/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli.handlers;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.cli.ArgumentValueConverter;
import org.jboss.as.cli.CommandArgument;
import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.ModelNodeFormatter;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.impl.DefaultCompleter;
import org.jboss.as.cli.impl.DefaultCompleter.CandidatesProvider;
import org.jboss.as.cli.operation.OperationFormatException;
import org.jboss.as.cli.operation.OperationRequestAddress;
import org.jboss.as.cli.operation.CommandLineParser;
import org.jboss.as.cli.operation.ParsedCommandLine;
import org.jboss.as.cli.operation.impl.DefaultCallbackHandler;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestAddress;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestBuilder;
import org.jboss.as.cli.parsing.ParserUtil;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;


/**
 *
 * @author Alexey Loubyansky
 */
public class GenericTypeOperationHandler extends BatchModeCommandHandler {

    protected final String commandName;
    protected final String type;
    protected final String idProperty;
    protected final OperationRequestAddress nodePath;
    protected final ArgumentWithValue profile;
    protected final ArgumentWithValue name;
    protected final ArgumentWithValue operation;

    protected final List<String> excludeOps;

    // help arguments
    protected final ArgumentWithoutValue helpProperties;
    protected final ArgumentWithoutValue helpCommands;

    // these are caching vars
    private final List<CommandArgument> staticArgs = new ArrayList<CommandArgument>();
    private Map<String, CommandArgument> nodeProps;
    private Map<String, Map<String, CommandArgument>> propsByOp;

    public GenericTypeOperationHandler(String nodeType, String idProperty) {
        this(nodeType, idProperty, Arrays.asList("read-attribute", "read-children-names", "read-children-resources",
                "read-children-types", "read-operation-description", "read-operation-names",
                "read-resource-description", "validate-address", "write-attribute"));
    }

    public GenericTypeOperationHandler(String nodeType, String idProperty, List<String> excludeOperations) {

        super("generic-type-operation", true);

        helpArg = new ArgumentWithoutValue(this, "--help", "-h") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(ctx.isDomainMode() && !profile.isValueComplete(ctx.getParsedCommandLine())) {
                    return false;
                }
                return super.canAppearNext(ctx);
            }
        };

        nodePath = new DefaultOperationRequestAddress();
        CommandLineParser.CallbackHandler handler = new DefaultCallbackHandler(nodePath);
        try {
            ParserUtil.parseOperationRequest(nodeType, handler);
        } catch (CommandFormatException e) {
            throw new IllegalArgumentException("Failed to parse nodeType: " + e.getMessage());
        }

        if(!nodePath.endsOnType()) {
            throw new IllegalArgumentException("The node path doesn't end on a type: '" + nodeType + "'");
        }
        this.type = nodePath.getNodeType();
        nodePath.toParentNode();
        addRequiredPath(nodePath);
        this.commandName = type;
        this.idProperty = idProperty;

        this.excludeOps = excludeOperations;

        profile = new ArgumentWithValue(this, new DefaultCompleter(new CandidatesProvider(){
            @Override
            public List<String> getAllCandidates(CommandContext ctx) {
                return Util.getNodeNames(ctx.getModelControllerClient(), null, "profile");
            }}), "--profile") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(!ctx.isDomainMode()) {
                    return false;
                }
                return super.canAppearNext(ctx);
            }
        };
        //profile.addCantAppearAfter(helpArg);

        operation = new ArgumentWithValue(this, new DefaultCompleter(new CandidatesProvider(){
                @Override
                public Collection<String> getAllCandidates(CommandContext ctx) {
                    DefaultOperationRequestAddress address = new DefaultOperationRequestAddress();
                    if(ctx.isDomainMode()) {
                        final String profileName = profile.getValue(ctx.getParsedCommandLine());
                        if(profileName == null) {
                            return Collections.emptyList();
                        }
                        address.toNode("profile", profileName);
                    }

                    for(OperationRequestAddress.Node node : nodePath) {
                        address.toNode(node.getType(), node.getName());
                    }
                    address.toNode(type, "?");
                    Collection<String> ops = ctx.getOperationCandidatesProvider().getOperationNames(ctx, address);
                    ops.removeAll(excludeOps);
                    return ops;
                }}), 0, "--operation") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(ctx.isDomainMode() && !profile.isValueComplete(ctx.getParsedCommandLine())) {
                    return false;
                }
                return super.canAppearNext(ctx);
            }
        };
        operation.addCantAppearAfter(helpArg);

        name = new ArgumentWithValue(this, new DefaultCompleter(new DefaultCompleter.CandidatesProvider() {
            @Override
            public List<String> getAllCandidates(CommandContext ctx) {
                ModelControllerClient client = ctx.getModelControllerClient();
                if (client == null) {
                    return Collections.emptyList();
                    }

                DefaultOperationRequestAddress address = new DefaultOperationRequestAddress();
                if(ctx.isDomainMode()) {
                    final String profileName = profile.getValue(ctx.getParsedCommandLine());
                    if(profile == null) {
                        return Collections.emptyList();
                    }
                    address.toNode("profile", profileName);
                }

                for(OperationRequestAddress.Node node : nodePath) {
                    address.toNode(node.getType(), node.getName());
                }

                return Util.getNodeNames(ctx.getModelControllerClient(), address, type);
                }
            }), (idProperty == null ? "--name" : "--" + idProperty)) {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(ctx.isDomainMode() && !profile.isValueComplete(ctx.getParsedCommandLine())) {
                    return false;
                }
                return super.canAppearNext(ctx);
            }
        };
        name.addCantAppearAfter(helpArg);

        helpArg.addCantAppearAfter(name);

        helpProperties = new ArgumentWithoutValue(this, "--properties");
        helpProperties.addRequiredPreceding(helpArg);
        helpProperties.addCantAppearAfter(operation);

        helpCommands = new ArgumentWithoutValue(this, "--commands");
        helpCommands.addRequiredPreceding(helpArg);
        helpCommands.addCantAppearAfter(operation);
        helpCommands.addCantAppearAfter(helpProperties);
        helpProperties.addCantAppearAfter(helpCommands);


        ///
        staticArgs.add(helpArg);
        staticArgs.add(helpCommands);
        staticArgs.add(helpProperties);
        staticArgs.add(profile);
        staticArgs.add(name);
        staticArgs.add(operation);
    }

    @Override
    public Collection<CommandArgument> getArguments(CommandContext ctx) {
        ParsedCommandLine args = ctx.getParsedCommandLine();
        try {
            if(!name.isValueComplete(args)) {
                return staticArgs;
            }
        } catch (CommandFormatException e) {
            return Collections.emptyList();
        }
        final String op = operation.getValue(args);
        return loadArguments(ctx, op).values();
    }

    private Map<String,CommandArgument> loadArguments(CommandContext ctx, String op) {
        if(op == null) {
            // list node properties
            if(nodeProps == null) {
                final List<Property> propList = getNodeProperties(ctx);
                final Map<String, CommandArgument> argMap = new HashMap<String, CommandArgument>(propList.size());
                for(int i = 0; i < propList.size(); ++i) {
                    final Property prop = propList.get(i);
                    final ModelNode propDescr = prop.getValue();
                    if(propDescr.has("access-type") && "read-write".equals(propDescr.get("access-type").asString())) {
                        ModelType type = null;
                        CommandLineCompleter valueCompleter = null;
                        ArgumentValueConverter valueConverter = ArgumentValueConverter.DEFAULT;
                        if(propDescr.has("type")) {
                            type = propDescr.get("type").asType();
                            if(ModelType.BOOLEAN == type) {
                                valueCompleter = SimpleTabCompleter.BOOLEAN;
                            //TODO } else if(ModelType.PROPERTY == type) {
                            } else if(prop.getName().endsWith("properties")) {
                                valueConverter = ArgumentValueConverter.PROPERTIES;
                            } else if(ModelType.LIST == type) {
                                valueConverter = ArgumentValueConverter.LIST;
                            }
                        }
                        final CommandArgument arg = new ArgumentWithValue(GenericTypeOperationHandler.this, valueCompleter, valueConverter, "--" + prop.getName());
                        argMap.put(arg.getFullName(), arg);
                    }
                }
                nodeProps = argMap;
            }
            return nodeProps;
        } else {
            // list operation properties
            if(propsByOp == null) {
                propsByOp = new HashMap<String, Map<String, CommandArgument>>();
            }
            Map<String, CommandArgument> opProps = propsByOp.get(op);
            if(opProps == null) {
                final ModelNode descr;
                try {
                    descr = getOperationDescription(ctx, op);
                } catch (IOException e1) {
                    return Collections.emptyMap();
                }

                if(descr == null || !descr.has("request-properties")) {
                    opProps = Collections.emptyMap();
                } else {
                    final List<Property> propList = descr.get("request-properties").asPropertyList();
                    opProps = new HashMap<String,CommandArgument>(propList.size());
                    for (Property prop : propList) {
                        final ModelNode propDescr = prop.getValue();
                        ModelType type = null;
                        CommandLineCompleter valueCompleter = null;
                        ArgumentValueConverter valueConverter = ArgumentValueConverter.DEFAULT;
                        if(propDescr.has("type")) {
                            type = propDescr.get("type").asType();
                            if(ModelType.BOOLEAN == type) {
                                valueCompleter = SimpleTabCompleter.BOOLEAN;
                            //TODO } else if(ModelType.PROPERTY == type) {
                            } else if(prop.getName().endsWith("properties")) {
                                valueConverter = ArgumentValueConverter.PROPERTIES;
                            } else if(ModelType.LIST == type) {
                                valueConverter = ArgumentValueConverter.LIST;
                            }
                        }
                        final CommandArgument arg = new ArgumentWithValue(GenericTypeOperationHandler.this, valueCompleter, valueConverter, "--" + prop.getName());
                        opProps.put(arg.getFullName(), arg);
                    }
                }
                propsByOp.put(op, opProps);
            }
            return opProps;
        }
    }

    @Override
    public boolean hasArgument(String name) {
        return true;
    }

    @Override
    public boolean hasArgument(int index) {
        return true;
    }

    public void addArgument(CommandArgument arg) {
    }

    @Override
    public ModelNode buildRequest(CommandContext ctx) throws CommandFormatException {
        final String operation = this.operation.getValue(ctx.getParsedCommandLine());
        if(operation == null) {
            return buildWritePropertyRequest(ctx);
        }
        return buildOperationRequest(ctx, operation);
    }

    @Override
    protected void handleResponse(CommandContext ctx, ModelNode opResult) {
        if (!Util.isSuccess(opResult)) {
            ctx.printLine(Util.getFailureDescription(opResult));
            return;
        }

        if(opResult.hasDefined(Util.RESULT)) {
            final ModelNode result = opResult.get(Util.RESULT);
            final ModelNodeFormatter formatter = ModelNodeFormatter.Factory.forType(result.getType());
            final StringBuilder buf = new StringBuilder();
            formatter.format(buf, 0, result);
            ctx.printLine(buf.toString());
        }
    }

    protected ModelNode buildWritePropertyRequest(CommandContext ctx) throws CommandFormatException {

        final String name = this.name.getValue(ctx.getParsedCommandLine(), true);

        ModelNode composite = new ModelNode();
        composite.get("operation").set("composite");
        composite.get("address").setEmptyList();
        ModelNode steps = composite.get("steps");

        ParsedCommandLine args = ctx.getParsedCommandLine();

        final String profile;
        if(ctx.isDomainMode()) {
            profile = this.profile.getValue(args);
            if(profile == null) {
                throw new OperationFormatException("--profile argument value is missing.");
            }
        } else {
            profile = null;
        }

        final Map<String,CommandArgument> nodeProps = loadArguments(ctx, null);
        for(String argName : args.getPropertyNames()) {
            if(argName.equals("--profile") || this.name.getFullName().equals(argName)) {
                continue;
            }

            final ArgumentWithValue arg = (ArgumentWithValue) nodeProps.get(argName);
            if(arg == null) {
                throw new CommandFormatException("Unrecognized argument name '" + argName + "'");
            }

            DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
            if (profile != null) {
                builder.addNode("profile", profile);
            }

            for(OperationRequestAddress.Node node : nodePath) {
                builder.addNode(node.getType(), node.getName());
            }
            builder.addNode(type, name);
            builder.setOperationName("write-attribute");
            final String propName;
            if(argName.charAt(1) == '-') {
                propName = argName.substring(2);
            } else {
                propName = argName.substring(1);
            }
            builder.addProperty("name", propName);

            final String valueString = args.getPropertyValue(argName);
            ModelNode nodeValue = arg.getValueConverter().fromString(valueString);
            builder.getModelNode().get("value").set(nodeValue);

            steps.add(builder.buildRequest());
        }

        return composite;
    }

    protected ModelNode buildOperationRequest(CommandContext ctx, final String operation) throws CommandFormatException {

        ParsedCommandLine args = ctx.getParsedCommandLine();

        DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
        if(ctx.isDomainMode()) {
            final String profile = this.profile.getValue(args);
            if(profile == null) {
                throw new OperationFormatException("Required argument --profile is missing.");
            }
            builder.addNode("profile", profile);
        }

        final String name = this.name.getValue(ctx.getParsedCommandLine(), true);

        for(OperationRequestAddress.Node node : nodePath) {
            builder.addNode(node.getType(), node.getName());
        }
        builder.addNode(type, name);
        builder.setOperationName(operation);

        final Map<String, CommandArgument> argsMap = loadArguments(ctx, operation);

        for(String argName : args.getPropertyNames()) {
            if(argName.equals("--profile")) {
                continue;
            }

            if(argsMap == null) {
                if(argName.equals(this.name.getFullName())) {
                    continue;
                }
                throw new CommandFormatException("Command '" + operation + "' is not expected to have arguments other than " + this.name.getFullName() + ".");
            }

            final ArgumentWithValue arg = (ArgumentWithValue) argsMap.get(argName);
            if(arg == null) {
                if(argName.equals(this.name.getFullName())) {
                    continue;
                }
                throw new CommandFormatException("Unrecognized argument " + argName + " for command '" + operation + "'.");
            }

            final String propName;
            if(argName.charAt(1) == '-') {
                propName = argName.substring(2);
            } else {
                propName = argName.substring(1);
            }

            final String valueString = args.getPropertyValue(argName);
            ModelNode nodeValue = arg.getValueConverter().fromString(valueString);
            builder.getModelNode().get(propName).set(nodeValue);
        }

        return builder.buildRequest();
    }

    protected void printHelp(CommandContext ctx) {

        ParsedCommandLine args = ctx.getParsedCommandLine();
        try {
            if(helpProperties.isPresent(args)) {
                try {
                    printProperties(ctx, getNodeProperties(ctx));
                } catch(Exception e) {
                    ctx.printLine("Failed to obtain the list or properties: " + e.getLocalizedMessage());
                    return;
                }
                return;
            }
        } catch (CommandFormatException e) {
            ctx.printLine(e.getLocalizedMessage());
            return;
        }

        try {
            if(helpCommands.isPresent(args)) {
                printCommands(ctx);
                return;
            }
        } catch (CommandFormatException e) {
            ctx.printLine(e.getLocalizedMessage());
            return;
        }

        final String operationName = operation.getValue(args);
        if(operationName == null) {
            printNodeDescription(ctx);
            return;
        }

        try {
            ModelNode result = getOperationDescription(ctx, operationName);
            if(!result.hasDefined("description")) {
                ctx.printLine("Operation description is not available.");
                return;
            }

            final StringBuilder buf = new StringBuilder();
            buf.append("\nDESCRIPTION:\n\n");
            buf.append(result.get("description").asString());
            ctx.printLine(buf.toString());

            if(result.hasDefined("request-properties")) {
                printProperties(ctx, result.get("request-properties").asPropertyList());
            } else {
                printProperties(ctx, Collections.<Property>emptyList());
            }
        } catch (Exception e) {
        }
    }

    protected void printProperties(CommandContext ctx, List<Property> props) {
        final Map<String, StringBuilder> requiredProps = new LinkedHashMap<String,StringBuilder>();
        requiredProps.put(this.name.getFullName(), new StringBuilder().append("Required argument in commands which identifies the instance to execute the command against."));
        final Map<String, StringBuilder> optionalProps = new LinkedHashMap<String, StringBuilder>();

        String accessType = null;
        for (Property attr : props) {
            final ModelNode value = attr.getValue();

            // filter metrics
            if (value.has("access-type")) {
                accessType = value.get("access-type").asString();
//                if("metric".equals(accessType)) {
//                    continue;
//                }
            }

            final boolean required = value.hasDefined("required") ? value.get("required").asBoolean() : false;
            final StringBuilder descr = new StringBuilder();

            final String type = value.has("type") ? value.get("type").asString() : "no type info";
            if (value.hasDefined("description")) {
                descr.append('(');
                descr.append(type);
                if(accessType != null) {
                    descr.append(',').append(accessType);
                }
                descr.append(") ");
                descr.append(value.get("description").asString());
            } else if(descr.length() == 0) {
                descr.append("no description.");
            }

            if(required) {
                if(idProperty != null && idProperty.equals(attr.getName())) {
                    if(descr.charAt(descr.length() - 1) != '.') {
                        descr.append('.');
                    }
                    requiredProps.get(this.name.getFullName()).insert(0, ' ').insert(0, descr.toString());
                } else {
                    requiredProps.put("--" + attr.getName(), descr);
                }
            } else {
                optionalProps.put("--" + attr.getName(), descr);
            }
        }

        ctx.printLine("\n");
        if(accessType == null) {
            ctx.printLine("REQUIRED ARGUMENTS:\n");
        }
        for(String argName : requiredProps.keySet()) {
            final StringBuilder prop = new StringBuilder();
            prop.append(' ').append(argName);
            int spaces = 28 - prop.length();
            do {
                prop.append(' ');
                --spaces;
            } while(spaces >= 0);
            prop.append("- ").append(requiredProps.get(argName));
            ctx.printLine(prop.toString());
        }

        if(!optionalProps.isEmpty()) {
            if(accessType == null ) {
                ctx.printLine("\n\nOPTIONAL ARGUMENTS:\n");
            }
            for(String argName : optionalProps.keySet()) {
                final StringBuilder prop = new StringBuilder();
                prop.append(' ').append(argName);
                int spaces = 28 - prop.length();
                do {
                    prop.append(' ');
                    --spaces;
                } while(spaces >= 0);
                prop.append("- ").append(optionalProps.get(argName));
                ctx.printLine(prop.toString());
            }
        }
    }

    protected void printNodeDescription(CommandContext ctx) {
        ModelNode request = initRequest(ctx);
        if(request == null) {
            return;
        }
        request.get("operation").set("read-resource-description");

        try {
            ModelNode result = ctx.getModelControllerClient().execute(request);
            if(!result.hasDefined("result")) {
                ctx.printLine("Node description is not available.");
                return;
            }
            result = result.get("result");
            if(!result.hasDefined("description")) {
                ctx.printLine("Node description is not available.");
                return;
            }
            ctx.printLine(result.get("description").asString());
        } catch (Exception e) {
        }
    }

    protected void printCommands(CommandContext ctx) {
        ModelNode request = initRequest(ctx);
        if(request == null) {
            return;
        }
        request.get("operation").set("read-operation-names");

        try {
            ModelNode result = ctx.getModelControllerClient().execute(request);
            if(!result.hasDefined("result")) {
                ctx.printLine("Operation names aren't available.");
                return;
            }
            final List<String> list = Util.getList(result);
            list.removeAll(this.excludeOps);
            list.add("To read the description of a specific command execute '" + this.commandName + " command_name --help'.");
            for(String name : list) {
                ctx.printLine(name);
            }
        } catch (Exception e) {
        }
    }

    protected List<Property> getNodeProperties(CommandContext ctx) {
        ModelNode request = initRequest(ctx);
        if(request == null) {
            return Collections.emptyList();
        }
        request.get("operation").set("read-resource-description");

        ModelNode result;
        try {
            result = ctx.getModelControllerClient().execute(request);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        if(!result.hasDefined("result")) {
            return Collections.emptyList();
        }
        result = result.get("result");
        if(!result.hasDefined("attributes")) {
            return Collections.emptyList();
        }

        return result.get("attributes").asPropertyList();
    }

    protected ModelNode getOperationDescription(CommandContext ctx, String operationName) throws IOException {
        ModelNode request = initRequest(ctx);
        if(request == null) {
            return null;
        }
        request.get("operation").set("read-operation-description");
        request.get("name").set(operationName);

        ModelNode result = ctx.getModelControllerClient().execute(request);
        if (!result.hasDefined("result")) {
            return null;
        }
        return result.get("result");
    }

    protected ModelNode initRequest(CommandContext ctx) {
        ModelNode request = new ModelNode();
        ModelNode address = request.get("address");
        if(ctx.isDomainMode()) {
            final String profileName = profile.getValue(ctx.getParsedCommandLine());
            if(profile == null) {
                ctx.printLine("--profile argument is required to get the node description.");
                return null;
            }
            address.add("profile", profileName);
        }
        for(OperationRequestAddress.Node node : nodePath) {
            address.add(node.getType(), node.getName());
        }
        address.add(type, "?");
        return request;
    }
}
