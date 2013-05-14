package com.twitter.mesos.scheduler;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.apache.zookeeper.data.ACL;

import com.twitter.common.application.ShutdownRegistry;
import com.twitter.common.application.modules.LifecycleModule;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.base.Command;
import com.twitter.common.inject.TimedInterceptor;
import com.twitter.common.net.pool.DynamicHostSet;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.util.Clock;
import com.twitter.common.zookeeper.Candidate;
import com.twitter.common.zookeeper.ServerSet;
import com.twitter.common.zookeeper.SingletonService;
import com.twitter.common.zookeeper.ZooKeeperClient;
import com.twitter.common_internal.zookeeper.TwitterServerSet;
import com.twitter.common_internal.zookeeper.TwitterServerSet.Service;
import com.twitter.common_internal.zookeeper.legacy.ServerSetMigrationModule.ServiceDiscovery;
import com.twitter.mesos.GuiceUtils;
import com.twitter.mesos.auth.AuthBindings;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.scheduler.CronJobManager.CronScheduler;
import com.twitter.mesos.scheduler.CronJobManager.CronScheduler.Cron4jScheduler;
import com.twitter.mesos.scheduler.Driver.DriverImpl;
import com.twitter.mesos.scheduler.MaintenanceController.MaintenanceControllerImpl;
import com.twitter.mesos.scheduler.MesosTaskFactory.MesosTaskFactoryImpl;
import com.twitter.mesos.scheduler.PulseMonitor.PulseMonitorImpl;
import com.twitter.mesos.scheduler.SchedulerLifecycle.DriverReference;
import com.twitter.mesos.scheduler.TaskAssigner.TaskAssignerImpl;
import com.twitter.mesos.scheduler.async.AsyncModule;
import com.twitter.mesos.scheduler.events.TaskEventModule;
import com.twitter.mesos.scheduler.filter.SchedulingFilterImpl;
import com.twitter.mesos.scheduler.http.ServletModule;
import com.twitter.mesos.scheduler.metadata.MetadataModule;
import com.twitter.mesos.scheduler.periodic.GcExecutorLauncher;
import com.twitter.mesos.scheduler.periodic.GcExecutorLauncher.GcExecutor;
import com.twitter.mesos.scheduler.periodic.PeriodicTaskModule;
import com.twitter.mesos.scheduler.quota.QuotaModule;
import com.twitter.mesos.scheduler.storage.AttributeStore;
import com.twitter.mesos.scheduler.storage.AttributeStore.AttributeStoreImpl;
import com.twitter.thrift.ServiceInstance;

/**
 * Binding module for the twitter mesos scheduler.
 */
public class SchedulerModule extends AbstractModule {
  private static final Logger LOG = Logger.getLogger(SchedulerModule.class.getName());

  @CmdLine(name = "executor_gc_interval",
      help = "Interval on which to run the GC executor on a host to clean up dead tasks.")
  private static final Arg<Amount<Long, Time>> EXECUTOR_GC_INTERVAL =
      Arg.create(Amount.of(1L, Time.HOURS));

  @CmdLine(name = "gc_executor_path", help = "Path to the gc executor launch script.")
  private static final Arg<String> GC_EXECUTOR_PATH = Arg.create(null);

  @VisibleForTesting
  enum AuthMode {
    UNSECURE,
    ANGRYBIRD_UNSECURE,
    SECURE
  }

  private final String clusterName;
  private final AuthMode authMode;

  SchedulerModule(String clusterName, AuthMode authMode) {
    this.clusterName = clusterName;
    this.authMode = authMode;
  }

  @Override
  protected void configure() {
    // Enable intercepted method timings and context classloader repair.
    TimedInterceptor.bind(binder());
    GuiceUtils.bindJNIContextClassLoader(binder(), Scheduler.class);
    GuiceUtils.bindExceptionTrap(binder(), Scheduler.class);

    bind(Key.get(String.class, ClusterName.class)).toInstance(clusterName);

    bind(Driver.class).to(DriverImpl.class);
    bind(DriverImpl.class).in(Singleton.class);

    bind(new TypeLiteral<Supplier<Optional<SchedulerDriver>>>() { }).to(DriverReference.class);
    bind(DriverReference.class).in(Singleton.class);

    bind(TaskAssigner.class).to(TaskAssignerImpl.class);
    bind(TaskAssignerImpl.class).in(Singleton.class);
    bind(MesosTaskFactory.class).to(MesosTaskFactoryImpl.class);

    // Bindings for MesosSchedulerImpl.
    switch(authMode) {
      case SECURE:
        LOG.info("Using secure authentication mode");
        AuthBindings.bindLdapAuth(binder());
        break;

      case UNSECURE:
        LOG.warning("Using unsecure authentication mode");
        AuthBindings.bindTestAuth(binder());
        break;

      case ANGRYBIRD_UNSECURE:
        LOG.warning("Using angrybird authentication mode");
        AuthBindings.bindAngryBirdAuth(binder());
        break;

       default:
         throw new IllegalArgumentException("Invalid authentication mode: " + authMode);
    }

    bind(SchedulerCore.class).to(SchedulerCoreImpl.class).in(Singleton.class);

    bind(new TypeLiteral<Optional<String>>() { }).annotatedWith(GcExecutor.class)
        .toInstance(Optional.fromNullable(GC_EXECUTOR_PATH.get()));
    bind(new TypeLiteral<PulseMonitor<String>>() { })
        .annotatedWith(GcExecutor.class)
        .toInstance(new PulseMonitorImpl<String>(EXECUTOR_GC_INTERVAL.get()));

    bind(CronScheduler.class).to(Cron4jScheduler.class);

    // Bindings for SchedulerCoreImpl.
    bind(CronJobManager.class).in(Singleton.class);
    // TODO(William Farner): Add a test that fails if CronJobManager is not wired for events.
    TaskEventModule.bindSubscriber(binder(), CronJobManager.class);
    bind(ImmediateJobManager.class).in(Singleton.class);

    // Filter layering: notifier filter -> base impl
    TaskEventModule.bind(binder(), SchedulingFilterImpl.class);
    bind(SchedulingFilterImpl.class).in(Singleton.class);

    install(new MetadataModule());

    // updaterTaskProvider handled in provider.

    bind(Scheduler.class).to(MesosSchedulerImpl.class);
    bind(MesosSchedulerImpl.class).in(Singleton.class);

    bind(new TypeLiteral<Function<TwitterTaskInfo, String>>() { }).to(TaskIdGenerator.class);

    // Bindings for StateManager
    bind(StateManager.class).to(StateManagerImpl.class);
    bind(Clock.class).toInstance(Clock.SYSTEM_CLOCK);
    bind(StateManagerImpl.class).in(Singleton.class);

    LifecycleModule.bindStartupAction(binder(), RegisterShutdownStackPrinter.class);

    bind(SchedulerLifecycle.class).in(Singleton.class);
    TaskEventModule.bindSubscriber(binder(), SchedulerLifecycle.class);
    bind(AttributeStore.Mutable.class).to(AttributeStoreImpl.class);
    bind(AttributeStoreImpl.class).in(Singleton.class);

    QuotaModule.bind(binder());
    PeriodicTaskModule.bind(binder());

    install(new ServletModule());

    bind(GcExecutorLauncher.class).in(Singleton.class);
    bind(UserTaskLauncher.class).in(Singleton.class);

    install(new AsyncModule());

    bind(MaintenanceController.class).to(MaintenanceControllerImpl.class);
    bind(MaintenanceControllerImpl.class).in(Singleton.class);

    bind(StatsProvider.class).toInstance(Stats.STATS_PROVIDER);
    TaskEventModule.bindSubscriber(binder(), TaskVars.class);
  }

  /**
   * Command to register a thread stack printer that identifies initiator of a shutdown.
   */
  private static class RegisterShutdownStackPrinter implements Command {
    private static final Function<StackTraceElement, String> STACK_ELEM_TOSTRING =
        new Function<StackTraceElement, String>() {
          @Override public String apply(StackTraceElement element) {
            return element.getClassName() + "." + element.getMethodName()
                + String.format("(%s:%s)", element.getFileName(), element.getLineNumber());
          }
        };

    private final ShutdownRegistry shutdownRegistry;

    @Inject
    RegisterShutdownStackPrinter(ShutdownRegistry shutdownRegistry) {
      this.shutdownRegistry = shutdownRegistry;
    }

    @Override
    public void execute() {
      shutdownRegistry.addAction(new Command() {
        @Override public void execute() {
          Thread thread = Thread.currentThread();
          String message = new StringBuilder()
              .append("Thread: ").append(thread.getName())
              .append(" (id ").append(thread.getId()).append(")")
              .append("\n")
              .append(Joiner.on("\n  ").join(
                  Iterables.transform(Arrays.asList(thread.getStackTrace()), STACK_ELEM_TOSTRING)))
              .toString();

          LOG.info("Shutdown initiated by: " + message);
        }
      });
    }
  }

  @Provides
  @Singleton
  SingletonService provideSingletonService(
      Service schedulerService,
      ServerSet serverSet,
      @ServiceDiscovery ZooKeeperClient client,
      @ServiceDiscovery List<ACL> acl) {

    // We vie for candidacy in the SD cluster since this is a private action, but we dual publish
    // our leadership in the compound ServerSet so that old mesos clients can still find us.
    // TODO(John Sirois): After the mesos client is deployed come back and eliminate dual
    // publishing.
    String path = TwitterServerSet.getPath(schedulerService);
    Candidate candidate = SingletonService.createSingletonCandidate(client, path, acl);
    return new SingletonService(serverSet, candidate);
  }

  @Provides
  @Singleton
  DynamicHostSet<ServiceInstance> provideSchedulerHostSet(
      Service schedulerService,
      @ServiceDiscovery ZooKeeperClient zkClient) {

    // For the leader-redirect servlet.
    return TwitterServerSet.create(zkClient, schedulerService);
  }

  @Provides
  @Singleton
  List<TaskLauncher> provideTaskLaunchers(
      GcExecutorLauncher gcLauncher,
      UserTaskLauncher userTaskLauncher) {

    return ImmutableList.of(gcLauncher, userTaskLauncher);
  }
}
