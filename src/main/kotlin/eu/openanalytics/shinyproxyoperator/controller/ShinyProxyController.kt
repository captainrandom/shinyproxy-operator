/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.ingres.IIngressController
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.system.exitProcess


class ShinyProxyController(private val channel: Channel<ShinyProxyEvent>,
                           private val kubernetesClient: KubernetesClient,
                           private val shinyProxyClient: ShinyProxyClient,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           private val ingressController: IIngressController,
                           private val resourceRetriever: ResourceRetriever,
                           private val shinyProxyLister: Lister<ShinyProxy>,
                           private val podRetriever: PodRetriever,
                           private val reconcileListener: IReconcileListener?) {

    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val serviceFactory = ServiceFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient)

    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info("Starting ShinyProxy Operator")
        GlobalScope.launch { scheduleAdditionalEvents() }
        while (true) {
            try {
                receiveAndHandleEvent()
            } catch (cancellationException: CancellationException) {
                logger.warn { "Controller cancelled -> stopping" }
                throw cancellationException
            }
        }
    }

    suspend fun receiveAndHandleEvent() {
        val event = channel.receive()

        try {
            when (event.eventType) {
                ShinyProxyEventType.ADD -> {
                    if (event.shinyProxy == null) {
                        logger.warn { "Event of type ADD should have shinyproxy attached to it." }
                        return
                    }
                    val newInstance = createNewInstance(event.shinyProxy)
                    val freshShinyProxy = refreshShinyProxy(event.shinyProxy)
                    reconcileSingleShinyProxyInstance(freshShinyProxy, newInstance)
                }
                ShinyProxyEventType.UPDATE_SPEC -> {
                    if (event.shinyProxy == null) {
                        logger.warn { "Event of type UPDATE_SPEC should have shinyproxy attached to it." }
                        return
                    }
                    val newInstance = createNewInstance(event.shinyProxy)
                    val freshShinyProxy = refreshShinyProxy(event.shinyProxy)
                    reconcileSingleShinyProxyInstance(freshShinyProxy, newInstance)
                }
                ShinyProxyEventType.DELETE -> {
                    // DELETE is not needed
                }
                ShinyProxyEventType.RECONCILE -> {
                    if (event.shinyProxy == null) {
                        logger.warn { "Event of type RECONCILE should have shinyProxy attached to it." }
                        return
                    }
                    if (event.shinyProxyInstance == null) {
                        logger.warn { "Event of type RECONCILE should have shinyProxyInstance attached to it." }
                        return
                    }
                    reconcileSingleShinyProxyInstance(event.shinyProxy, event.shinyProxyInstance)
                }
                ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES -> {
                    checkForObsoleteInstances()
                }
            }
        } catch (e: KubernetesClientException) {
            logger.warn(e) { "Caught KubernetesClientException while processing event $event. Exiting process." }
            exitProcess(1)
        } catch (e: Exception) {
            logger.warn(e) { "Caught an exception while processing event $event. Continuing processing other events." }
        }
    }

    private fun createNewInstance(shinyProxy: ShinyProxy): ShinyProxyInstance {
        val existingInstance = shinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)

        if (existingInstance != null && existingInstance.isLatestInstance) {
            logger.warn { "Trying to create new instance which already exists and is the latest instance" }
            return existingInstance
        } else if (existingInstance != null && !existingInstance.isLatestInstance) {
            // make the old existing instance again the latest instance
            updateStatus(shinyProxy) {
                it.status.instances.forEach { inst -> inst.isLatestInstance = false }
                existingInstance.isLatestInstance = true
            }
            ingressController.reconcile(shinyProxy)
            return existingInstance
        }

        // create new instance and add it to the list of instances
        // initial the instance is not the latest. Only when the ReplicaSet is created and fully running
        // the latestInstance marker will change to the new instance.
        val newInstance = ShinyProxyInstance(shinyProxy.hashOfCurrentSpec, false)
        updateStatus(shinyProxy) {
            it.status.instances.add(newInstance)
        }

        return newInstance
    }

    private fun updateStatus(shinyProxy: ShinyProxy, updater: (ShinyProxy) -> Unit) {
        val freshShinyProxy = refreshShinyProxy(shinyProxy)
        updater(freshShinyProxy)
        try {
            shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).updateStatus(freshShinyProxy)
        } catch (e: KubernetesClientException) {
            // TODO handle this
            throw e
        }
    }

    private fun refreshShinyProxy(shinyProxy: ShinyProxy): ShinyProxy {
        return shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).withName(shinyProxy.metadata.name).get()
    }

    private fun updateLatestMarker(shinyProxy: ShinyProxy) {
        val latestInstance = shinyProxy.status.instances.firstOrNull { it.hashOfSpec == shinyProxy.hashOfCurrentSpec }
                ?: return

        if (latestInstance.isLatestInstance) {
            // already updated marker
            return
        }

        updateStatus(shinyProxy) {
            it.status.instances.forEach { inst -> inst.isLatestInstance = false }
            it.status.instances.first { inst -> inst.hashOfSpec == latestInstance.hashOfSpec }.isLatestInstance = true
        }

    }

    private suspend fun reconcileSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }

        if (!shinyProxy.status.instances.contains(shinyProxyInstance)) {
            logger.info { "Cannot reconcile ShinProxyInstance ${shinyProxyInstance.hashOfSpec} because it is begin deleted." }
            return
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        if (!Readiness.isReady(replicaSets[0])) {
            // do no proceed until replicaset is ready
            return
        }

        logger.debug { "ReplicaSet is ready -> proceed with reconcile" }

        updateLatestMarker(shinyProxy)

        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        ingressController.reconcile(shinyProxy)
        podRetriever.addNamespaces(shinyProxy.namespacesOfCurrentInstance)
        reconcileListener?.onInstanceFullyReconciled(shinyProxy, shinyProxyInstance)
    }

    private fun checkForObsoleteInstances() {
        for (shinyProxy in shinyProxyLister.list()) {
            if (shinyProxy.status.instances.size > 1) {
                // this SP has more than one instance -> check if some of them are obsolete
                // take a copy of the list to check to prevent concurrent modification
                val instancesToCheck = shinyProxy.status.instances.toList()
                for (shinyProxyInstance in instancesToCheck) {
                    if (shinyProxyInstance.isLatestInstance || shinyProxyInstance.hashOfSpec == shinyProxy.hashOfCurrentSpec) {
                        // shinyProxyInstance is either the latest or the soon to be latest instance
                        continue
                    }

                    val pods = podRetriever.getPodsForShinyProxyInstance(shinyProxy, shinyProxyInstance)

                    if (pods.isEmpty()) {
                        logger.info { "ShinyProxyInstance ${shinyProxyInstance.hashOfSpec} has no running apps and is not the latest version => removing this instance" }
                        deleteSingleShinyProxyInstance(shinyProxy, shinyProxyInstance)
                        updateStatus(shinyProxy) {
                            it.status.instances.remove(shinyProxyInstance)
                        }
                    } else {
                        logger.debug { "ShinyProxyInstance ${shinyProxyInstance.hashOfSpec} has ${pods.size} running apps => not removing this instance" }
                    }
                }
            }
        }
    }

    // TODO timer and extract from this class?
    private suspend fun scheduleAdditionalEvents() {
        while (true) {
            channel.send(ShinyProxyEvent(ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES, null, null))
            delay(3000)
        }
    }

    private fun deleteSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "DeleteSingleShinyProxyInstance: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }
        for (service in resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(service).delete()
        }
        for (replicaSet in resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(replicaSet).delete()
        }
        for (configMap in resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(configMap).delete()
        }
    }


}
