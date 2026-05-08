package com.mappedsky.neo4j.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Java agent that guards Neo4j's system database from administrative commands
 * (CREATE USER, DROP USER, ALTER USER, etc.) by users who lack the {@code _admin}
 * username suffix.
 *
 * <p>Load via {@code -javaagent:/path/to/system-guard-agent.jar} in {@code neo4j.conf}:
 * <pre>server.jvm.additional=-javaagent:/var/lib/neo4j/plugins/system-guard-agent.jar</pre>
 *
 * <p>Policy enforced:
 * <ul>
 *   <li>{@code _admin} users – may execute any administrative command on the system DB.</li>
 *   <li>Built-in {@code neo4j} user – exempt to allow initial bootstrap configuration.</li>
 *   <li>All others – blocked with {@code AuthorizationViolationException}.</li>
 * </ul>
 *
 * <h2>Interception point (verified against neo4j-cypher-5.26.0.jar)</h2>
 * <p>The agent targets
 * {@code UpdatingSystemCommandExecutionPlanBase.runSpecific(...)}. This is the outermost
 * Cypher-level entry point for write administrative commands, and it runs before Neo4j
 * executes the inner system-update subquery. Read-only admin commands such as
 * {@code SHOW USERS} use a different execution plan and pass through untouched.
 */
public class SystemDatabaseGuardAgent {

    public static void premain(String args, Instrumentation instrumentation) {
        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.NoOp.INSTANCE)
                .type(named("org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlanBase"))
                .transform((builder, type, loader, module, domain) ->
                        builder.method(named("runSpecific"))
                               .intercept(Advice.to(AdminCommandAdvice.class)))
                .installOn(instrumentation);
    }
}
