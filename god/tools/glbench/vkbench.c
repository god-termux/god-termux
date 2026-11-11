/* vkbench: the same two workloads as glbench, in native Vulkan (libvulkan.so loaded by path), offscreen 1280x720 RGBA8.
 * Test 1: fullscreen quad with the same heavy fragment shader (precision mediump), 4 draws per submit like glbench's 4 draws per glFinish.
 * Test 2: draw calls into a 32x32 viewport, push constant changed per draw, command buffer re-recorded and submitted per 2000 draws.
 * Shaders are compiled to SPIR-V by glslangValidator and embedded (shaders.h, see build.sh). */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include "shaders.h"

#define W 1280
#define H 720
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { printf("   ОШИБКА: %s -> %d\n", #x, (int)r_); exit(4); } } while (0)

static PFN_vkGetInstanceProcAddr gipa;
#define F(name) static PFN_##name name
F(vkCreateInstance); F(vkEnumeratePhysicalDevices); F(vkGetPhysicalDeviceProperties); F(vkGetPhysicalDeviceQueueFamilyProperties);
F(vkGetPhysicalDeviceMemoryProperties); F(vkCreateDevice); F(vkGetDeviceQueue); F(vkCreateImage); F(vkGetImageMemoryRequirements);
F(vkAllocateMemory); F(vkBindImageMemory); F(vkCreateImageView); F(vkCreateRenderPass); F(vkCreateFramebuffer); F(vkCreateShaderModule);
F(vkCreatePipelineLayout); F(vkCreateGraphicsPipelines); F(vkCreateCommandPool); F(vkAllocateCommandBuffers); F(vkBeginCommandBuffer);
F(vkEndCommandBuffer); F(vkCmdBeginRenderPass); F(vkCmdEndRenderPass); F(vkCmdBindPipeline); F(vkCmdPushConstants); F(vkCmdDraw);
F(vkCmdSetViewport); F(vkCmdSetScissor); F(vkQueueSubmit); F(vkCreateFence); F(vkWaitForFences); F(vkResetFences); F(vkResetCommandBuffer);
F(vkDeviceWaitIdle);
#define L(i, name) do { name = (PFN_##name)gipa(i, #name); if (!name) { printf("   ОШИБКА: нет функции " #name "\n"); exit(3); } } while (0)

typedef struct { float r[2]; float t; } PC;

int main(int argc, char** argv) {
  const char* lib = argc > 1 ? argv[1] : "/system/lib64/libvulkan.so";
  printf("== vkbench\n   Vulkan: %s\n", lib);
  void* h = dlopen(lib, RTLD_NOW | RTLD_LOCAL); if (!h) { printf("   ОШИБКА: %s\n", dlerror()); return 3; }
  gipa = (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr"); if (!gipa) { printf("   ОШИБКА: vkGetInstanceProcAddr\n"); return 3; }
  vkCreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
  VkApplicationInfo ai = { VK_STRUCTURE_TYPE_APPLICATION_INFO, 0, "vkbench", 1, "none", 1, VK_API_VERSION_1_1 };
  VkInstanceCreateInfo ici = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, 0, 0, &ai };
  VkInstance inst; CK(vkCreateInstance(&ici, 0, &inst));
  L(inst, vkEnumeratePhysicalDevices); L(inst, vkGetPhysicalDeviceProperties); L(inst, vkGetPhysicalDeviceQueueFamilyProperties);
  L(inst, vkGetPhysicalDeviceMemoryProperties); L(inst, vkCreateDevice); L(inst, vkGetDeviceQueue); L(inst, vkCreateImage);
  L(inst, vkGetImageMemoryRequirements); L(inst, vkAllocateMemory); L(inst, vkBindImageMemory); L(inst, vkCreateImageView);
  L(inst, vkCreateRenderPass); L(inst, vkCreateFramebuffer); L(inst, vkCreateShaderModule); L(inst, vkCreatePipelineLayout);
  L(inst, vkCreateGraphicsPipelines); L(inst, vkCreateCommandPool); L(inst, vkAllocateCommandBuffers); L(inst, vkBeginCommandBuffer);
  L(inst, vkEndCommandBuffer); L(inst, vkCmdBeginRenderPass); L(inst, vkCmdEndRenderPass); L(inst, vkCmdBindPipeline);
  L(inst, vkCmdPushConstants); L(inst, vkCmdDraw); L(inst, vkCmdSetViewport); L(inst, vkCmdSetScissor); L(inst, vkQueueSubmit);
  L(inst, vkCreateFence); L(inst, vkWaitForFences); L(inst, vkResetFences); L(inst, vkResetCommandBuffer); L(inst, vkDeviceWaitIdle);

  uint32_t n = 1; VkPhysicalDevice pd; VkResult er = vkEnumeratePhysicalDevices(inst, &n, &pd);
  if ((er != VK_SUCCESS && er != VK_INCOMPLETE) || n < 1) { printf("   ОШИБКА: нет устройств Vulkan\n"); return 4; }
  VkPhysicalDeviceProperties pp; vkGetPhysicalDeviceProperties(pd, &pp);
  printf("   VkDevice: %s, Vulkan %u.%u.%u, драйвер 0x%x\n", pp.deviceName, VK_VERSION_MAJOR(pp.apiVersion), VK_VERSION_MINOR(pp.apiVersion),
         VK_VERSION_PATCH(pp.apiVersion), pp.driverVersion);
  uint32_t qn = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, 0); VkQueueFamilyProperties qf[16]; if (qn > 16) qn = 16;
  vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, qf);
  uint32_t fam = 0; for (uint32_t i = 0; i < qn; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { fam = i; break; }
  float prio = 1.0f; VkDeviceQueueCreateInfo qci = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, 0, 0, fam, 1, &prio };
  VkDeviceCreateInfo dci = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, 0, 0, 1, &qci };
  VkDevice dev; CK(vkCreateDevice(pd, &dci, 0, &dev)); VkQueue q; vkGetDeviceQueue(dev, fam, 0, &q);

  VkImageCreateInfo imi = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, 0, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM, { W, H, 1 }, 1, 1,
    VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_SHARING_MODE_EXCLUSIVE };
  VkImage img; CK(vkCreateImage(dev, &imi, 0, &img)); VkMemoryRequirements mr; vkGetImageMemoryRequirements(dev, img, &mr);
  VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd, &mp); uint32_t mt = 0xffffffff;
  for (uint32_t i = 0; i < mp.memoryTypeCount; i++) if ((mr.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { mt = i; break; }
  if (mt == 0xffffffff) for (uint32_t i = 0; i < mp.memoryTypeCount; i++) if (mr.memoryTypeBits & (1u << i)) { mt = i; break; }
  VkMemoryAllocateInfo mai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, 0, mr.size, mt }; VkDeviceMemory mem;
  CK(vkAllocateMemory(dev, &mai, 0, &mem)); CK(vkBindImageMemory(dev, img, mem, 0));
  VkImageViewCreateInfo vci = { VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, 0, 0, img, VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
    { 0, 0, 0, 0 }, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } }; VkImageView view; CK(vkCreateImageView(dev, &vci, 0, &view));

  VkAttachmentDescription ad = { 0, VK_FORMAT_R8G8B8A8_UNORM, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE,
    VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
  VkAttachmentReference ar = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
  VkSubpassDescription sd = { 0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, 0, 1, &ar };
  VkRenderPassCreateInfo rpi = { VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, 0, 0, 1, &ad, 1, &sd }; VkRenderPass rp; CK(vkCreateRenderPass(dev, &rpi, 0, &rp));
  VkFramebufferCreateInfo fbi = { VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, 0, 0, rp, 1, &view, W, H, 1 }; VkFramebuffer fb; CK(vkCreateFramebuffer(dev, &fbi, 0, &fb));

  VkShaderModuleCreateInfo smv = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, 0, 0, vk_vert_spv_len, (const uint32_t*)vk_vert_spv };
  VkShaderModuleCreateInfo smh = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, 0, 0, vk_heavy_frag_spv_len, (const uint32_t*)vk_heavy_frag_spv };
  VkShaderModuleCreateInfo smf = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, 0, 0, vk_flat_frag_spv_len, (const uint32_t*)vk_flat_frag_spv };
  VkShaderModule mv, mh, mf; CK(vkCreateShaderModule(dev, &smv, 0, &mv)); CK(vkCreateShaderModule(dev, &smh, 0, &mh)); CK(vkCreateShaderModule(dev, &smf, 0, &mf));
  VkPushConstantRange pcr = { VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PC) };
  VkPipelineLayoutCreateInfo pli = { VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, 0, 0, 0, 0, 1, &pcr }; VkPipelineLayout pl; CK(vkCreatePipelineLayout(dev, &pli, 0, &pl));

  VkPipelineVertexInputStateCreateInfo vi = { VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
  VkPipelineInputAssemblyStateCreateInfo ia = { VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, 0, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP };
  VkPipelineViewportStateCreateInfo vp = { VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, 0, 0, 1, 0, 1, 0 };
  VkPipelineRasterizationStateCreateInfo rs = { VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, 0, 0, 0, 0, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, 0, 0, 0, 0, 1.0f };
  VkPipelineMultisampleStateCreateInfo ms = { VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, 0, 0, VK_SAMPLE_COUNT_1_BIT };
  VkPipelineColorBlendAttachmentState cba = { 0, 0, 0, 0, 0, 0, 0, 0xf };
  VkPipelineColorBlendStateCreateInfo cb = { VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, 0, 0, 0, 0, 1, &cba };
  VkDynamicState dyn[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
  VkPipelineDynamicStateCreateInfo ds = { VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, 0, 0, 2, dyn };
  VkPipelineShaderStageCreateInfo st[2] = { { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, 0, 0, VK_SHADER_STAGE_VERTEX_BIT, mv, "main" },
                                            { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, 0, 0, VK_SHADER_STAGE_FRAGMENT_BIT, mh, "main" } };
  VkGraphicsPipelineCreateInfo gpi = { VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, 0, 0, 2, st, &vi, &ia, 0, &vp, &rs, &ms, 0, &cb, &ds, pl, rp, 0 };
  VkPipeline pheavy, pflat; CK(vkCreateGraphicsPipelines(dev, 0, 1, &gpi, 0, &pheavy)); st[1].module = mf; CK(vkCreateGraphicsPipelines(dev, 0, 1, &gpi, 0, &pflat));

  VkCommandPoolCreateInfo cpi = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, 0, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, fam }; VkCommandPool cp; CK(vkCreateCommandPool(dev, &cpi, 0, &cp));
  VkCommandBufferAllocateInfo cai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, 0, cp, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 }; VkCommandBuffer cmd; CK(vkAllocateCommandBuffers(dev, &cai, &cmd));
  VkFenceCreateInfo fci = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO }; VkFence fence; CK(vkCreateFence(dev, &fci, 0, &fence));
  VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, 0, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
  VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO, 0, 0, 0, 0, 1, &cmd };

  /* test 1: heavy shader, 4 fullscreen draws per submit */
  int frames = 0; double t0 = now(), t1;
  do {
    CK(vkResetCommandBuffer(cmd, 0)); CK(vkBeginCommandBuffer(cmd, &bi));
    VkRenderPassBeginInfo rb = { VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, 0, rp, fb, { { 0, 0 }, { W, H } } };
    vkCmdBeginRenderPass(cmd, &rb, VK_SUBPASS_CONTENTS_INLINE); vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pheavy);
    VkViewport v = { 0, 0, W, H, 0, 1 }; VkRect2D sc = { { 0, 0 }, { W, H } }; vkCmdSetViewport(cmd, 0, 1, &v); vkCmdSetScissor(cmd, 0, 1, &sc);
    for (int i = 0; i < 4; i++) { PC pc = { { W, H }, frames * 0.01f }; vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof pc, &pc); vkCmdDraw(cmd, 4, 1, 0, 0); frames++; }
    vkCmdEndRenderPass(cmd); CK(vkEndCommandBuffer(cmd)); CK(vkResetFences(dev, 1, &fence)); CK(vkQueueSubmit(q, 1, &si, fence));
    CK(vkWaitForFences(dev, 1, &fence, 1, 5000000000ull)); t1 = now();
  } while (t1 - t0 < 3.0);
  printf("   [тяжёлый шейдер 1280x720, 48 итераций] %.1f кадров/с, %.0f Мпикс/с (за %.1f с, %d кадров)\n", frames / (t1 - t0), frames * (double)W * H / (t1 - t0) / 1e6, t1 - t0, frames);

  /* test 2: small draws, push constant per draw, re-recorded every 2000 draws */
  long calls = 0; t0 = now();
  do {
    CK(vkResetCommandBuffer(cmd, 0)); CK(vkBeginCommandBuffer(cmd, &bi));
    VkRenderPassBeginInfo rb = { VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, 0, rp, fb, { { 0, 0 }, { W, H } } };
    vkCmdBeginRenderPass(cmd, &rb, VK_SUBPASS_CONTENTS_INLINE); vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pflat);
    VkViewport v = { 0, 0, 32, 32, 0, 1 }; VkRect2D sc = { { 0, 0 }, { 32, 32 } }; vkCmdSetViewport(cmd, 0, 1, &v); vkCmdSetScissor(cmd, 0, 1, &sc);
    for (int i = 0; i < 2000; i++) { PC pc = { { 32, 32 }, (i & 255) / 255.0f }; vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof pc, &pc); vkCmdDraw(cmd, 4, 1, 0, 0); calls++; }
    vkCmdEndRenderPass(cmd); CK(vkEndCommandBuffer(cmd)); CK(vkResetFences(dev, 1, &fence)); CK(vkQueueSubmit(q, 1, &si, fence));
    CK(vkWaitForFences(dev, 1, &fence, 1, 5000000000ull)); t1 = now();
  } while (t1 - t0 < 2.0);
  printf("   [вызовы отрисовки, вид 32x32] %.0f вызовов/с (%.1f мкс на вызов)\n", calls / (t1 - t0), (t1 - t0) / calls * 1e6);
  vkDeviceWaitIdle(dev);
  return 0;
}
