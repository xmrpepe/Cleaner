// Vulkan laboratory: capability probe + compute (FMA throughput) and offscreen
// blended fill-rate benchmarks. libvulkan.so is loaded with dlopen so the app runs
// (with Vulkan reported as unavailable) on devices or emulators without a driver.
#include "common.h"

#include <dlfcn.h>
#include <vector>
#include <vulkan/vulkan.h>

#include "spirv_fill_frag.h"
#include "spirv_fma_comp.h"
#include "spirv_fullscreen_vert.h"

namespace pf {

namespace {

struct Vk {
    void* lib = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkEnumerateInstanceExtensionProperties enumerateInstanceExtensionProperties = nullptr;
    // Instance level
    PFN_vkDestroyInstance destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceFeatures getPhysicalDeviceFeatures = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 getPhysicalDeviceFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceFormatProperties getPhysicalDeviceFormatProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    // Device level
    PFN_vkDestroyDevice destroyDevice = nullptr;
    PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
    PFN_vkCreateBuffer createBuffer = nullptr;
    PFN_vkDestroyBuffer destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements getBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindBufferMemory bindBufferMemory = nullptr;
    PFN_vkMapMemory mapMemory = nullptr;
    PFN_vkUnmapMemory unmapMemory = nullptr;
    PFN_vkCreateShaderModule createShaderModule = nullptr;
    PFN_vkDestroyShaderModule destroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout createDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout destroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout createPipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout destroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines createComputePipelines = nullptr;
    PFN_vkCreateGraphicsPipelines createGraphicsPipelines = nullptr;
    PFN_vkDestroyPipeline destroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool createDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool destroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets allocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets updateDescriptorSets = nullptr;
    PFN_vkCreateCommandPool createCommandPool = nullptr;
    PFN_vkDestroyCommandPool destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers allocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer endCommandBuffer = nullptr;
    PFN_vkResetCommandBuffer resetCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline cmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets cmdBindDescriptorSets = nullptr;
    PFN_vkCmdPushConstants cmdPushConstants = nullptr;
    PFN_vkCmdDispatch cmdDispatch = nullptr;
    PFN_vkCmdBeginRenderPass cmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass cmdEndRenderPass = nullptr;
    PFN_vkCmdDraw cmdDraw = nullptr;
    PFN_vkCmdSetViewport cmdSetViewport = nullptr;
    PFN_vkCmdSetScissor cmdSetScissor = nullptr;
    PFN_vkQueueSubmit queueSubmit = nullptr;
    PFN_vkQueueWaitIdle queueWaitIdle = nullptr;
    PFN_vkCreateFence createFence = nullptr;
    PFN_vkDestroyFence destroyFence = nullptr;
    PFN_vkWaitForFences waitForFences = nullptr;
    PFN_vkResetFences resetFences = nullptr;
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkCreateImageView createImageView = nullptr;
    PFN_vkDestroyImageView destroyImageView = nullptr;
    PFN_vkCreateRenderPass createRenderPass = nullptr;
    PFN_vkDestroyRenderPass destroyRenderPass = nullptr;
    PFN_vkCreateFramebuffer createFramebuffer = nullptr;
    PFN_vkDestroyFramebuffer destroyFramebuffer = nullptr;

    bool loadGlobal() {
        lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
        if (!getInstanceProcAddr) return false;
#define PF_G(name, fn) fn = reinterpret_cast<PFN_vk##name>(getInstanceProcAddr(nullptr, "vk" #name))
        PF_G(EnumerateInstanceVersion, enumerateInstanceVersion);
        PF_G(CreateInstance, createInstance);
        PF_G(EnumerateInstanceExtensionProperties, enumerateInstanceExtensionProperties);
#undef PF_G
        return createInstance != nullptr;
    }

    void loadInstance(VkInstance inst) {
#define PF_I(name, fn) fn = reinterpret_cast<PFN_vk##name>(getInstanceProcAddr(inst, "vk" #name))
        PF_I(DestroyInstance, destroyInstance);
        PF_I(EnumeratePhysicalDevices, enumeratePhysicalDevices);
        PF_I(GetPhysicalDeviceProperties, getPhysicalDeviceProperties);
        PF_I(GetPhysicalDeviceProperties2, getPhysicalDeviceProperties2);
        PF_I(GetPhysicalDeviceFeatures, getPhysicalDeviceFeatures);
        PF_I(GetPhysicalDeviceFeatures2, getPhysicalDeviceFeatures2);
        PF_I(GetPhysicalDeviceMemoryProperties, getPhysicalDeviceMemoryProperties);
        PF_I(GetPhysicalDeviceQueueFamilyProperties, getPhysicalDeviceQueueFamilyProperties);
        PF_I(GetPhysicalDeviceFormatProperties, getPhysicalDeviceFormatProperties);
        PF_I(EnumerateDeviceExtensionProperties, enumerateDeviceExtensionProperties);
        PF_I(CreateDevice, createDevice);
        PF_I(GetDeviceProcAddr, getDeviceProcAddr);
#undef PF_I
    }

    void loadDevice(VkDevice dev) {
#define PF_D(name, fn) fn = reinterpret_cast<PFN_vk##name>(getDeviceProcAddr(dev, "vk" #name))
        PF_D(DestroyDevice, destroyDevice);
        PF_D(GetDeviceQueue, getDeviceQueue);
        PF_D(CreateBuffer, createBuffer);
        PF_D(DestroyBuffer, destroyBuffer);
        PF_D(GetBufferMemoryRequirements, getBufferMemoryRequirements);
        PF_D(AllocateMemory, allocateMemory);
        PF_D(FreeMemory, freeMemory);
        PF_D(BindBufferMemory, bindBufferMemory);
        PF_D(MapMemory, mapMemory);
        PF_D(UnmapMemory, unmapMemory);
        PF_D(CreateShaderModule, createShaderModule);
        PF_D(DestroyShaderModule, destroyShaderModule);
        PF_D(CreateDescriptorSetLayout, createDescriptorSetLayout);
        PF_D(DestroyDescriptorSetLayout, destroyDescriptorSetLayout);
        PF_D(CreatePipelineLayout, createPipelineLayout);
        PF_D(DestroyPipelineLayout, destroyPipelineLayout);
        PF_D(CreateComputePipelines, createComputePipelines);
        PF_D(CreateGraphicsPipelines, createGraphicsPipelines);
        PF_D(DestroyPipeline, destroyPipeline);
        PF_D(CreateDescriptorPool, createDescriptorPool);
        PF_D(DestroyDescriptorPool, destroyDescriptorPool);
        PF_D(AllocateDescriptorSets, allocateDescriptorSets);
        PF_D(UpdateDescriptorSets, updateDescriptorSets);
        PF_D(CreateCommandPool, createCommandPool);
        PF_D(DestroyCommandPool, destroyCommandPool);
        PF_D(AllocateCommandBuffers, allocateCommandBuffers);
        PF_D(BeginCommandBuffer, beginCommandBuffer);
        PF_D(EndCommandBuffer, endCommandBuffer);
        PF_D(ResetCommandBuffer, resetCommandBuffer);
        PF_D(CmdBindPipeline, cmdBindPipeline);
        PF_D(CmdBindDescriptorSets, cmdBindDescriptorSets);
        PF_D(CmdPushConstants, cmdPushConstants);
        PF_D(CmdDispatch, cmdDispatch);
        PF_D(CmdBeginRenderPass, cmdBeginRenderPass);
        PF_D(CmdEndRenderPass, cmdEndRenderPass);
        PF_D(CmdDraw, cmdDraw);
        PF_D(CmdSetViewport, cmdSetViewport);
        PF_D(CmdSetScissor, cmdSetScissor);
        PF_D(QueueSubmit, queueSubmit);
        PF_D(QueueWaitIdle, queueWaitIdle);
        PF_D(CreateFence, createFence);
        PF_D(DestroyFence, destroyFence);
        PF_D(WaitForFences, waitForFences);
        PF_D(ResetFences, resetFences);
        PF_D(CreateImage, createImage);
        PF_D(DestroyImage, destroyImage);
        PF_D(GetImageMemoryRequirements, getImageMemoryRequirements);
        PF_D(BindImageMemory, bindImageMemory);
        PF_D(CreateImageView, createImageView);
        PF_D(DestroyImageView, destroyImageView);
        PF_D(CreateRenderPass, createRenderPass);
        PF_D(DestroyRenderPass, destroyRenderPass);
        PF_D(CreateFramebuffer, createFramebuffer);
        PF_D(DestroyFramebuffer, destroyFramebuffer);
#undef PF_D
    }

    ~Vk() {
        if (lib) dlclose(lib);
    }
};

std::string versionString(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." + std::to_string(VK_API_VERSION_MINOR(v)) + "." +
           std::to_string(VK_API_VERSION_PATCH(v));
}

const char* deviceTypeName(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "Integrated GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "Discrete GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "Virtual GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU (software)";
        default: return "Other";
    }
}

std::string errorJson(const std::string& msg) {
    Json j;
    j.beginObject().kv("available", false).kv("error", msg).endObject();
    return j.str();
}

// Owns an instance + the first physical device. Destroyed automatically.
struct Context {
    Vk vk;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    uint32_t apiVersion = VK_API_VERSION_1_0;
    std::string error;

    bool init() {
        if (!vk.loadGlobal()) { error = "libvulkan.so not available"; return false; }
        uint32_t loaderVersion = VK_API_VERSION_1_0;
        if (vk.enumerateInstanceVersion) vk.enumerateInstanceVersion(&loaderVersion);
        apiVersion = loaderVersion >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 :
                     loaderVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "PixelForge";
        app.applicationVersion = 1;
        app.pEngineName = "PixelForgeLab";
        app.apiVersion = apiVersion;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        VkResult r = vk.createInstance(&ci, nullptr, &instance);
        if (r != VK_SUCCESS) { error = "vkCreateInstance failed: " + std::to_string(r); return false; }
        vk.loadInstance(instance);
        uint32_t count = 0;
        vk.enumeratePhysicalDevices(instance, &count, nullptr);
        if (count == 0) { error = "no Vulkan physical devices"; return false; }
        std::vector<VkPhysicalDevice> devs(count);
        vk.enumeratePhysicalDevices(instance, &count, devs.data());
        phys = devs[0];
        return true;
    }

    ~Context() {
        if (instance && vk.destroyInstance) vk.destroyInstance(instance, nullptr);
    }
};

// Owns a logical device with one queue supporting the requested flags.
struct Device {
    Context& c;
    VkDevice dev = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;

    explicit Device(Context& ctx) : c(ctx) {}

    bool init(VkQueueFlags flags) {
        uint32_t qc = 0;
        c.vk.getPhysicalDeviceQueueFamilyProperties(c.phys, &qc, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qc);
        c.vk.getPhysicalDeviceQueueFamilyProperties(c.phys, &qc, qf.data());
        bool found = false;
        for (uint32_t i = 0; i < qc; ++i) {
            if ((qf[i].queueFlags & flags) == flags) { family = i; found = true; break; }
        }
        if (!found) return false;
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = family;
        qci.queueCount = 1;
        qci.pQueuePriorities = &prio;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        if (c.vk.createDevice(c.phys, &dci, nullptr, &dev) != VK_SUCCESS) return false;
        c.vk.loadDevice(dev);
        c.vk.getDeviceQueue(dev, family, 0, &queue);
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pci.queueFamilyIndex = family;
        return c.vk.createCommandPool(dev, &pci, nullptr, &pool) == VK_SUCCESS;
    }

    int32_t memoryType(uint32_t bits, VkMemoryPropertyFlags props) {
        VkPhysicalDeviceMemoryProperties mp;
        c.vk.getPhysicalDeviceMemoryProperties(c.phys, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) return static_cast<int32_t>(i);
        }
        return -1;
    }

    VkShaderModule shader(const uint32_t* code, size_t bytes) {
        VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        ci.codeSize = bytes;
        ci.pCode = code;
        VkShaderModule m = VK_NULL_HANDLE;
        c.vk.createShaderModule(dev, &ci, nullptr, &m);
        return m;
    }

    ~Device() {
        if (dev) {
            if (pool) c.vk.destroyCommandPool(dev, pool, nullptr);
            c.vk.destroyDevice(dev, nullptr);
        }
    }
};

void formatSupport(Context& c, Json& j, const char* name, VkFormat f) {
    VkFormatProperties p{};
    c.vk.getPhysicalDeviceFormatProperties(c.phys, f, &p);
    const auto o = p.optimalTilingFeatures;
    j.beginObject()
            .kv("format", name)
            .kv("sampled", (o & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0)
            .kv("colorAttachment", (o & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0)
            .kv("blend", (o & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0)
            .kv("storage", (o & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0)
            .kv("depthStencil", (o & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0)
            .kv("linearFilter", (o & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0)
            .endObject();
}

}  // namespace

std::string vulkanProbe() {
    Context c;
    if (!c.init()) return errorJson(c.error);

    VkPhysicalDeviceProperties props{};
    c.vk.getPhysicalDeviceProperties(c.phys, &props);

    VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    bool haveProps2 = c.vk.getPhysicalDeviceProperties2 && props.apiVersion >= VK_API_VERSION_1_1;
    if (haveProps2) {
        subgroup.pNext = props.apiVersion >= VK_API_VERSION_1_2 ? &driver : nullptr;
        VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        p2.pNext = &subgroup;
        c.vk.getPhysicalDeviceProperties2(c.phys, &p2);
    }

    VkPhysicalDeviceFeatures feats{};
    c.vk.getPhysicalDeviceFeatures(c.phys, &feats);
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    bool haveF12 = c.vk.getPhysicalDeviceFeatures2 && props.apiVersion >= VK_API_VERSION_1_2;
    if (haveF12) {
        VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        f2.pNext = &f12;
        c.vk.getPhysicalDeviceFeatures2(c.phys, &f2);
    }

    Json j;
    j.beginObject()
            .kv("available", true)
            .kv("instanceApi", versionString(c.apiVersion))
            .kv("deviceName", props.deviceName)
            .kv("deviceType", deviceTypeName(props.deviceType))
            .kv("vendorId", props.vendorID)
            .kv("deviceId", props.deviceID)
            .kv("apiVersion", versionString(props.apiVersion))
            .kv("driverVersionRaw", props.driverVersion);
    if (haveProps2 && props.apiVersion >= VK_API_VERSION_1_2) {
        j.kv("driverName", driver.driverName)
                .kv("driverInfo", driver.driverInfo)
                .kv("driverId", static_cast<int32_t>(driver.driverID))
                .kv("conformance", std::to_string(driver.conformanceVersion.major) + "." +
                                   std::to_string(driver.conformanceVersion.minor) + "." +
                                   std::to_string(driver.conformanceVersion.subminor) + "." +
                                   std::to_string(driver.conformanceVersion.patch));
    }
    if (haveProps2) {
        j.kv("subgroupSize", subgroup.subgroupSize)
                .kv("subgroupStages", subgroup.supportedStages)
                .kv("subgroupOps", subgroup.supportedOperations);
    }

    const auto& L = props.limits;
    j.key("limits").beginObject()
            .kv("maxImageDimension2D", L.maxImageDimension2D)
            .kv("maxImageDimension3D", L.maxImageDimension3D)
            .kv("maxImageArrayLayers", L.maxImageArrayLayers)
            .kv("maxComputeSharedMemorySize", L.maxComputeSharedMemorySize)
            .kv("maxComputeWorkGroupInvocations", L.maxComputeWorkGroupInvocations)
            .kv("maxComputeWorkGroupSizeX", L.maxComputeWorkGroupSize[0])
            .kv("maxComputeWorkGroupCountX", L.maxComputeWorkGroupCount[0])
            .kv("maxBoundDescriptorSets", L.maxBoundDescriptorSets)
            .kv("maxPushConstantsSize", L.maxPushConstantsSize)
            .kv("maxSamplerAnisotropy", static_cast<double>(L.maxSamplerAnisotropy))
            .kv("maxColorAttachments", L.maxColorAttachments)
            .kv("maxViewports", L.maxViewports)
            .kv("maxFramebufferWidth", L.maxFramebufferWidth)
            .kv("maxStorageBufferRange", L.maxStorageBufferRange)
            .kv("timestampPeriodNs", static_cast<double>(L.timestampPeriod))
            .kv("framebufferColorSampleCounts", static_cast<uint32_t>(L.framebufferColorSampleCounts))
            .endObject();

    j.key("features").beginObject()
            .kv("geometryShader", feats.geometryShader == VK_TRUE)
            .kv("tessellationShader", feats.tessellationShader == VK_TRUE)
            .kv("samplerAnisotropy", feats.samplerAnisotropy == VK_TRUE)
            .kv("textureCompressionASTC_LDR", feats.textureCompressionASTC_LDR == VK_TRUE)
            .kv("textureCompressionETC2", feats.textureCompressionETC2 == VK_TRUE)
            .kv("textureCompressionBC", feats.textureCompressionBC == VK_TRUE)
            .kv("multiDrawIndirect", feats.multiDrawIndirect == VK_TRUE)
            .kv("shaderFloat64", feats.shaderFloat64 == VK_TRUE)
            .kv("shaderInt64", feats.shaderInt64 == VK_TRUE)
            .kv("shaderInt16", feats.shaderInt16 == VK_TRUE)
            .kv("robustBufferAccess", feats.robustBufferAccess == VK_TRUE)
            .kv("fillModeNonSolid", feats.fillModeNonSolid == VK_TRUE)
            .kv("depthClamp", feats.depthClamp == VK_TRUE);
    if (haveF12) {
        j.kv("shaderFloat16", f12.shaderFloat16 == VK_TRUE)
                .kv("shaderInt8", f12.shaderInt8 == VK_TRUE)
                .kv("descriptorIndexing", f12.descriptorIndexing == VK_TRUE)
                .kv("timelineSemaphore", f12.timelineSemaphore == VK_TRUE)
                .kv("bufferDeviceAddress", f12.bufferDeviceAddress == VK_TRUE)
                .kv("vulkanMemoryModel", f12.vulkanMemoryModel == VK_TRUE);
    }
    j.endObject();

    VkPhysicalDeviceMemoryProperties mem{};
    c.vk.getPhysicalDeviceMemoryProperties(c.phys, &mem);
    j.key("memoryHeaps").beginArray();
    for (uint32_t i = 0; i < mem.memoryHeapCount; ++i) {
        j.beginObject()
                .kv("sizeBytes", static_cast<uint64_t>(mem.memoryHeaps[i].size))
                .kv("deviceLocal", (mem.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0)
                .endObject();
    }
    j.endArray();
    j.kv("memoryTypeCount", mem.memoryTypeCount);

    uint32_t qc = 0;
    c.vk.getPhysicalDeviceQueueFamilyProperties(c.phys, &qc, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qc);
    c.vk.getPhysicalDeviceQueueFamilyProperties(c.phys, &qc, qf.data());
    j.key("queueFamilies").beginArray();
    for (auto& q : qf) {
        j.beginObject()
                .kv("count", q.queueCount)
                .kv("graphics", (q.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0)
                .kv("compute", (q.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0)
                .kv("transfer", (q.queueFlags & VK_QUEUE_TRANSFER_BIT) != 0)
                .kv("timestampBits", q.timestampValidBits)
                .endObject();
    }
    j.endArray();

    j.key("formats").beginArray();
    formatSupport(c, j, "R8G8B8A8_UNORM", VK_FORMAT_R8G8B8A8_UNORM);
    formatSupport(c, j, "A2B10G10R10_UNORM", VK_FORMAT_A2B10G10R10_UNORM_PACK32);
    formatSupport(c, j, "R16G16B16A16_SFLOAT", VK_FORMAT_R16G16B16A16_SFLOAT);
    formatSupport(c, j, "R32G32B32A32_SFLOAT", VK_FORMAT_R32G32B32A32_SFLOAT);
    formatSupport(c, j, "B10G11R11_UFLOAT", VK_FORMAT_B10G11R11_UFLOAT_PACK32);
    formatSupport(c, j, "ASTC_4x4_UNORM", VK_FORMAT_ASTC_4x4_UNORM_BLOCK);
    formatSupport(c, j, "ASTC_8x8_SRGB", VK_FORMAT_ASTC_8x8_SRGB_BLOCK);
    formatSupport(c, j, "ETC2_R8G8B8A8_UNORM", VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK);
    formatSupport(c, j, "BC7_UNORM", VK_FORMAT_BC7_UNORM_BLOCK);
    formatSupport(c, j, "D32_SFLOAT", VK_FORMAT_D32_SFLOAT);
    formatSupport(c, j, "D24_UNORM_S8_UINT", VK_FORMAT_D24_UNORM_S8_UINT);
    j.endArray();

    uint32_t ec = 0;
    c.vk.enumerateDeviceExtensionProperties(c.phys, nullptr, &ec, nullptr);
    std::vector<VkExtensionProperties> exts(ec);
    c.vk.enumerateDeviceExtensionProperties(c.phys, nullptr, &ec, exts.data());
    j.key("deviceExtensions").beginArray();
    for (auto& e : exts) j.value(e.extensionName);
    j.endArray();

    uint32_t iec = 0;
    if (c.vk.enumerateInstanceExtensionProperties) {
        c.vk.enumerateInstanceExtensionProperties(nullptr, &iec, nullptr);
        std::vector<VkExtensionProperties> iexts(iec);
        c.vk.enumerateInstanceExtensionProperties(nullptr, &iec, iexts.data());
        j.key("instanceExtensions").beginArray();
        for (auto& e : iexts) j.value(e.extensionName);
        j.endArray();
    }
    j.endObject();
    return j.str();
}

std::string vulkanComputeBench(int durationMs) {
    Context c;
    if (!c.init()) return errorJson(c.error);
    Device d(c);
    if (!d.init(VK_QUEUE_COMPUTE_BIT)) return errorJson("no compute queue / device creation failed");
    auto& vk = c.vk;

    constexpr uint32_t kLocal = 128;
    constexpr uint32_t kGroups = 2048;
    constexpr uint32_t kInvocations = kLocal * kGroups;
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(kInvocations) * 16;

    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = bytes;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    VkBuffer buffer = VK_NULL_HANDLE;
    vk.createBuffer(d.dev, &bci, nullptr, &buffer);
    VkMemoryRequirements req;
    vk.getBufferMemoryRequirements(d.dev, buffer, &req);
    int32_t mt = d.memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mt < 0) { vk.destroyBuffer(d.dev, buffer, nullptr); return errorJson("no host-visible memory type"); }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = static_cast<uint32_t>(mt);
    VkDeviceMemory memory = VK_NULL_HANDLE;
    vk.allocateMemory(d.dev, &mai, nullptr, &memory);
    vk.bindBufferMemory(d.dev, buffer, memory, 0);
    void* mapped = nullptr;
    vk.mapMemory(d.dev, memory, 0, bytes, 0, &mapped);
    auto* f = static_cast<float*>(mapped);
    for (uint32_t i = 0; i < kInvocations * 4; ++i) f[i] = static_cast<float>(i % 97) * 0.001f;
    vk.unmapMemory(d.dev, memory);

    VkDescriptorSetLayoutBinding binding{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dslci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dslci.bindingCount = 1;
    dslci.pBindings = &binding;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    vk.createDescriptorSetLayout(d.dev, &dslci, nullptr, &dsl);

    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &dsl;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    vk.createPipelineLayout(d.dev, &plci, nullptr, &layout);

    VkShaderModule sm = d.shader(kFmaComp, sizeof(kFmaComp));
    VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, sm, "main", nullptr};
    cpci.layout = layout;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult pr = vk.createComputePipelines(d.dev, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline);

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = 1;
    dpci.poolSizeCount = 1;
    dpci.pPoolSizes = &ps;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    vk.createDescriptorPool(d.dev, &dpci, nullptr, &dpool);
    VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = dpool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &dsl;
    VkDescriptorSet set = VK_NULL_HANDLE;
    vk.allocateDescriptorSets(d.dev, &dsai, &set);
    VkDescriptorBufferInfo dbi{buffer, 0, bytes};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = set;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    w.pBufferInfo = &dbi;
    vk.updateDescriptorSets(d.dev, 1, &w, 0, nullptr);

    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = d.pool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    VkCommandBuffer cb = VK_NULL_HANDLE;
    vk.allocateCommandBuffers(d.dev, &cbai, &cb);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence = VK_NULL_HANDLE;
    vk.createFence(d.dev, &fci, nullptr, &fence);

    double gflops = 0;
    int64_t dispatches = 0;
    double busySeconds = 0;
    std::string err;
    if (pr != VK_SUCCESS || !pipeline) {
        err = "compute pipeline creation failed";
    } else {
        // Each dispatch: kInvocations * iterations * 64 FLOPs. Keep each submit short
        // (~tens of ms) so the driver watchdog and UI never stall.
        uint32_t iterations = 256;
        const int64_t end = nowNs() + static_cast<int64_t>(durationMs) * 1000000LL;
        double flops = 0;
        while (nowNs() < end && !gCancel.load()) {
            VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vk.resetCommandBuffer(cb, 0);
            vk.beginCommandBuffer(cb, &bi);
            vk.cmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vk.cmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
            vk.cmdPushConstants(cb, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t), &iterations);
            vk.cmdDispatch(cb, kGroups, 1, 1);
            vk.endCommandBuffer(cb);
            VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cb;
            vk.resetFences(d.dev, 1, &fence);
            const int64_t t0 = nowNs();
            if (vk.queueSubmit(d.queue, 1, &si, fence) != VK_SUCCESS) { err = "queue submit failed"; break; }
            if (vk.waitForFences(d.dev, 1, &fence, VK_TRUE, 5'000'000'000ULL) != VK_SUCCESS) { err = "GPU timeout"; break; }
            const double dt = static_cast<double>(nowNs() - t0) / 1e9;
            busySeconds += dt;
            flops += static_cast<double>(kInvocations) * iterations * 64.0;
            ++dispatches;
            // Adapt work size toward ~20 ms per submit.
            if (dt < 0.010 && iterations < 65536) iterations *= 2;
        }
        if (busySeconds > 0) gflops = flops / busySeconds / 1e9;
    }

    vk.destroyFence(d.dev, fence, nullptr);
    vk.destroyDescriptorPool(d.dev, dpool, nullptr);
    if (pipeline) vk.destroyPipeline(d.dev, pipeline, nullptr);
    vk.destroyShaderModule(d.dev, sm, nullptr);
    vk.destroyPipelineLayout(d.dev, layout, nullptr);
    vk.destroyDescriptorSetLayout(d.dev, dsl, nullptr);
    vk.destroyBuffer(d.dev, buffer, nullptr);
    vk.freeMemory(d.dev, memory, nullptr);

    if (!err.empty() && dispatches == 0) return errorJson(err);
    Json j;
    j.beginObject()
            .kv("available", true)
            .kv("gflops", gflops)
            .kv("dispatches", dispatches)
            .kv("gpuBusySeconds", busySeconds)
            .kv("cancelled", gCancel.load());
    if (!err.empty()) j.kv("warning", err);
    j.endObject();
    return j.str();
}

std::string vulkanFillBench(int durationMs, int width, int height) {
    Context c;
    if (!c.init()) return errorJson(c.error);
    Device d(c);
    if (!d.init(VK_QUEUE_GRAPHICS_BIT)) return errorJson("no graphics queue / device creation failed");
    auto& vk = c.vk;
    const VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;

    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = fmt;
    ici.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage image = VK_NULL_HANDLE;
    if (vk.createImage(d.dev, &ici, nullptr, &image) != VK_SUCCESS) return errorJson("image creation failed");
    VkMemoryRequirements req;
    vk.getImageMemoryRequirements(d.dev, image, &req);
    int32_t mt = d.memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = static_cast<uint32_t>(mt < 0 ? 0 : mt);
    VkDeviceMemory memory = VK_NULL_HANDLE;
    vk.allocateMemory(d.dev, &mai, nullptr, &memory);
    vk.bindImageMemory(d.dev, image, memory, 0);

    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = fmt;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    vk.createImageView(d.dev, &vci, nullptr, &view);

    VkAttachmentDescription att{};
    att.format = fmt;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;
    VkRenderPassCreateInfo rpci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpci.attachmentCount = 1;
    rpci.pAttachments = &att;
    rpci.subpassCount = 1;
    rpci.pSubpasses = &sub;
    VkRenderPass rp = VK_NULL_HANDLE;
    vk.createRenderPass(d.dev, &rpci, nullptr, &rp);

    VkFramebufferCreateInfo fbci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbci.renderPass = rp;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &view;
    fbci.width = static_cast<uint32_t>(width);
    fbci.height = static_cast<uint32_t>(height);
    fbci.layers = 1;
    VkFramebuffer fb = VK_NULL_HANDLE;
    vk.createFramebuffer(d.dev, &fbci, nullptr, &fb);

    VkPushConstantRange pcr{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    vk.createPipelineLayout(d.dev, &plci, nullptr, &layout);

    VkShaderModule vs = d.shader(kFullscreenVert, sizeof(kFullscreenVert));
    VkShaderModule fs = d.shader(kFillFrag, sizeof(kFillFrag));
    VkPipelineShaderStageCreateInfo stages[2] = {
            {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, vs, "main", nullptr},
            {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", nullptr}};
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vps{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vps.viewportCount = 1;
    vps.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.blendEnable = VK_TRUE;
    cba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    cba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    cba.colorBlendOp = VK_BLEND_OP_ADD;
    cba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    cba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    cba.alphaBlendOp = VK_BLEND_OP_ADD;
    cba.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &cba;
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo gpci{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpci.stageCount = 2;
    gpci.pStages = stages;
    gpci.pVertexInputState = &vi;
    gpci.pInputAssemblyState = &ia;
    gpci.pViewportState = &vps;
    gpci.pRasterizationState = &rs;
    gpci.pMultisampleState = &ms;
    gpci.pColorBlendState = &cb;
    gpci.pDynamicState = &ds;
    gpci.layout = layout;
    gpci.renderPass = rp;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult pr = vk.createGraphicsPipelines(d.dev, VK_NULL_HANDLE, 1, &gpci, nullptr, &pipeline);

    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = d.pool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vk.allocateCommandBuffers(d.dev, &cbai, &cmd);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence = VK_NULL_HANDLE;
    vk.createFence(d.dev, &fci, nullptr, &fence);

    std::string err;
    double pixels = 0, busy = 0;
    int64_t frames = 0;
    std::vector<double> frameMs;
    if (pr != VK_SUCCESS || !pipeline) {
        err = "graphics pipeline creation failed";
    } else {
        constexpr uint32_t kLayers = 32;  // full-screen blended layers per "frame"
        const int64_t end = nowNs() + static_cast<int64_t>(durationMs) * 1000000LL;
        while (nowNs() < end && !gCancel.load()) {
            VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vk.resetCommandBuffer(cmd, 0);
            vk.beginCommandBuffer(cmd, &bi);
            VkClearValue clear{};
            clear.color = {{0.f, 0.f, 0.f, 1.f}};
            VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rbi.renderPass = rp;
            rbi.framebuffer = fb;
            rbi.renderArea = {{0, 0}, {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}};
            rbi.clearValueCount = 1;
            rbi.pClearValues = &clear;
            vk.cmdBeginRenderPass(cmd, &rbi, VK_SUBPASS_CONTENTS_INLINE);
            vk.cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkViewport vp{0, 0, static_cast<float>(width), static_cast<float>(height), 0, 1};
            VkRect2D sc{{0, 0}, {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}};
            vk.cmdSetViewport(cmd, 0, 1, &vp);
            vk.cmdSetScissor(cmd, 0, 1, &sc);
            for (uint32_t l = 0; l < kLayers; ++l) {
                float layer = static_cast<float>(l) * 0.37f + static_cast<float>(frames) * 0.01f;
                vk.cmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float), &layer);
                vk.cmdDraw(cmd, 3, 1, 0, 0);
            }
            vk.cmdEndRenderPass(cmd);
            vk.endCommandBuffer(cmd);
            VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            vk.resetFences(d.dev, 1, &fence);
            const int64_t t0 = nowNs();
            if (vk.queueSubmit(d.queue, 1, &si, fence) != VK_SUCCESS) { err = "queue submit failed"; break; }
            if (vk.waitForFences(d.dev, 1, &fence, VK_TRUE, 5'000'000'000ULL) != VK_SUCCESS) { err = "GPU timeout"; break; }
            const double dt = static_cast<double>(nowNs() - t0) / 1e9;
            busy += dt;
            frameMs.push_back(dt * 1000.0);
            pixels += static_cast<double>(width) * height * kLayers;
            ++frames;
        }
    }

    vk.destroyFence(d.dev, fence, nullptr);
    if (pipeline) vk.destroyPipeline(d.dev, pipeline, nullptr);
    vk.destroyShaderModule(d.dev, vs, nullptr);
    vk.destroyShaderModule(d.dev, fs, nullptr);
    vk.destroyPipelineLayout(d.dev, layout, nullptr);
    vk.destroyFramebuffer(d.dev, fb, nullptr);
    vk.destroyRenderPass(d.dev, rp, nullptr);
    vk.destroyImageView(d.dev, view, nullptr);
    vk.destroyImage(d.dev, image, nullptr);
    vk.freeMemory(d.dev, memory, nullptr);

    if (!err.empty() && frames == 0) return errorJson(err);
    Json j;
    j.beginObject()
            .kv("available", true)
            .kv("width", static_cast<int32_t>(width))
            .kv("height", static_cast<int32_t>(height))
            .kv("frames", frames)
            .kv("gpixelsPerSecond", busy > 0 ? pixels / busy / 1e9 : 0.0)
            .kv("cancelled", gCancel.load());
    j.key("frameMs").beginArray();
    for (double v : frameMs) j.value(v);
    j.endArray();
    if (!err.empty()) j.kv("warning", err);
    j.endObject();
    return j.str();
}

}  // namespace pf
