<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import ToggleSwitch from 'primevue/toggleswitch'
import Checkbox from 'primevue/checkbox'
import RadioButton from 'primevue/radiobutton'
import { useConfirm } from 'primevue/useconfirm'
import Select from 'primevue/select'
import MultiSelect from 'primevue/multiselect'
import InputNumber from 'primevue/inputnumber'
import Panel from 'primevue/panel'

import Button from 'primevue/button'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import ProviderTag from '@/components/ProviderTag.vue'
import OptionCard from '@/components/OptionCard.vue'
import CronHelp from '@/components/CronHelp.vue'
import type { CloudProvider, CrontabSyncMode } from '@/__generated/model/enums'
import type { LocalCronForm } from '@/utils/crontabForm'

const props = defineProps<{
  visible: boolean
  isEditing: boolean
  saving: boolean
  form: LocalCronForm
  formErrors: Record<string, string>
  timeZones: ReadonlyArray<string>
  accountOptions: ReadonlyArray<{ label: string; value: number; provider?: CloudProvider }>
  formAlbumOptions: ReadonlyArray<{ label: string; value: number }>
  targetPathMountWarning: boolean
  validateCronForm: (fields?: readonly string[]) => boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit'): void
}>()

const { t } = useI18n()
const confirm = useConfirm()

const visibleProxy = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const form = toRef(props, 'form')
const savedPhotos = ref(false)
const photosSwitchKey = ref(0)
const formErrors = toRef(props, 'formErrors')
const accountOptions = computed(() => [...props.accountOptions])
const selectedAccount = computed(() =>
  accountOptions.value.find((account) => account.value === form.value.accountId),
)
const formAlbumOptions = computed(() => [...props.formAlbumOptions])
const timeZones = computed(() => [...props.timeZones])

const steps = ['basic', 'scope', 'mode', 'schedule'] as const
type Step = (typeof steps)[number]
const step = ref<Step>('basic')
const slideDir = ref<'slide-left' | 'slide-right'>('slide-left')
const stepIndex = computed(() => steps.indexOf(step.value))
const isLastStep = computed(() => stepIndex.value === steps.length - 1)

const stepTitle = computed(() => t(`cronform.step.${step.value}`))

const stepFields: Partial<Record<Step, readonly string[]>> = {
  basic: ['name', 'accountId'],
  schedule: ['expression', 'timeZone', 'targetPath'],
}

const isICloud = computed(() => selectedAccount.value?.provider === 'ICLOUD')
watch(
  isICloud,
  (value) => {
    if (!props.isEditing && value && form.value.syncMode === 'TIMELINE')
      form.value.syncMode = 'CURSOR'
  },
  { immediate: true },
)

const mirrorSupported = computed(
  () => selectedAccount.value?.provider !== 'XIAOMI' || form.value.syncMode !== 'CURSOR',
)
watch(
  mirrorSupported,
  (supported) => {
    if (!props.isEditing && !supported)
      form.value.config = { ...form.value.config, syncCloudDeletes: false }
  },
  { immediate: true },
)

const recommendedMode = computed<CrontabSyncMode>(() => (isICloud.value ? 'CURSOR' : 'TIMELINE'))

const syncModeCards = computed(() =>
  (
    [
      ['FULL', 'Full', 'pi-database'],
      ['TIMELINE', 'Timeline', 'pi-history'],
      ['CURSOR', 'Cursor', 'pi-bolt'],
    ] as const
  )
    .filter(([value]) => !isICloud.value || value !== 'TIMELINE')
    .map(([value, key, icon]) => ({
      value,
      icon,
      label: t(`cronform.advanced.syncMode${key}`),
      desc: t(`cronform.advanced.syncMode${key}Hint`),
      badge:
        value === 'CURSOR'
          ? ('beta' as const)
          : recommendedMode.value === value
            ? ('recommended' as const)
            : null,
    })),
)

function goToStep(target: Step) {
  slideDir.value = steps.indexOf(target) > stepIndex.value ? 'slide-left' : 'slide-right'
  step.value = target
}

function nextStep() {
  const fields = stepFields[step.value]
  if (fields && !props.validateCronForm(fields)) return
  if (!isLastStep.value) goToStep(steps[stepIndex.value + 1]!)
}

function goBack() {
  if (stepIndex.value > 0) goToStep(steps[stepIndex.value - 1]!)
}

function selectSyncMode(mode: CrontabSyncMode) {
  if (!props.isEditing) form.value.syncMode = mode
}

function setSynologyPhotos(enabled: boolean) {
  if (form.value.config.synologyPhotos === enabled) return
  if (!props.isEditing || enabled === savedPhotos.value) {
    form.value.config = { ...form.value.config, synologyPhotos: enabled }
    return
  }
  // ToggleSwitch keeps an internal value; remount it while confirmation is pending.
  photosSwitchKey.value++
  const pendingForm = form.value
  confirm.require({
    header: t(
      enabled
        ? 'cronform.toggle.synologyPhotosEnableTitle'
        : 'cronform.toggle.synologyPhotosDisableTitle',
    ),
    message: t(
      enabled
        ? 'cronform.toggle.synologyPhotosEnableHint'
        : 'cronform.toggle.synologyPhotosDisableHint',
    ),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('common.action.cancel'),
    acceptLabel: t('common.action.confirm'),
    rejectProps: { severity: 'secondary', text: true },
    accept: () => {
      if (props.visible && form.value === pendingForm)
        form.value.config = { ...form.value.config, synologyPhotos: enabled }
    },
  })
}

watch(formErrors, (errors) => {
  const keys = Object.keys(errors)
  if (!keys.length) return
  const target = steps.find((s) => keys.some((key) => stepFields[s]?.includes(key)))
  if (target && target !== step.value) goToStep(target)
})

// 仅显示 Cron 帮助；保存路径始终是统一根目录。
const showCronHelp = ref(false)

function toggleCronHelp() {
  showCronHelp.value = !showCronHelp.value
}

function openCronHelp() {
  showCronHelp.value = true
}

function closeDialog() {
  visibleProxy.value = false
}

watch(
  () => props.visible,
  (val) => {
    if (val) {
      savedPhotos.value = form.value.config.synologyPhotos === true
      step.value = 'basic'
      slideDir.value = 'slide-left'
      return
    }
    showCronHelp.value = false
  },
  { immediate: true },
)
</script>

<template>
  <Dialog
    v-model:visible="visibleProxy"
    modal
    :class="['transition-all duration-300', showCronHelp ? 'w-full sm:w-250' : 'w-full sm:w-130']"
  >
    <template #header>
      <div class="flex items-center gap-3 min-w-0">
        <span class="font-semibold truncate">{{
          props.isEditing ? t('cronform.title.edit') : t('cronform.title.create')
        }}</span>
        <div class="flex items-center gap-1.5 shrink-0">
          <span
            v-for="(s, i) in steps"
            :key="s"
            class="h-1.5 rounded-full transition-all duration-200"
            :class="[
              i === stepIndex
                ? 'w-4 bg-slate-700 dark:bg-slate-200'
                : i < stepIndex
                  ? 'w-1.5 bg-slate-400 dark:bg-slate-500'
                  : 'w-1.5 bg-slate-300 dark:bg-slate-600',
            ]"
          />
        </div>
        <span class="text-[13px] text-slate-500 dark:text-slate-400 truncate">{{ stepTitle }}</span>
      </div>
    </template>

    <div class="flex gap-8 items-stretch max-h-[65vh]">
      <div class="flex-1 min-w-0 overflow-x-hidden overflow-y-auto pr-1">
        <Transition :name="slideDir" mode="out-in">
          <!-- 基本信息 -->
          <div v-if="step === 'basic'" key="basic" class="space-y-4 pt-2">
            <div class="space-y-2">
              <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                t('common.field.name')
              }}</label>
              <InputText
                v-model="form.name"
                :placeholder="t('cronform.field.namePlaceholder')"
                class="w-full"
                autofocus
              />
              <div v-if="formErrors.name" class="text-[13px] text-red-600">
                {{ formErrors.name }}
              </div>
            </div>

            <div class="space-y-2">
              <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                t('common.field.description')
              }}</label>
              <Textarea
                v-model="form.description"
                rows="3"
                autoResize
                :placeholder="t('common.field.optional')"
                class="w-full"
              />
            </div>

            <div class="space-y-2">
              <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                t('cronform.field.account')
              }}</label>
              <Select
                v-model="form.accountId"
                :options="accountOptions"
                optionLabel="label"
                optionValue="value"
                :placeholder="t('cronform.field.accountPlaceholder')"
                class="w-full"
                :disabled="props.isEditing"
              >
                <template #value="slotProps">
                  <div v-if="selectedAccount" class="flex min-w-0 items-center gap-2">
                    <span class="truncate">{{ selectedAccount.label }}</span>
                    <ProviderTag :provider="selectedAccount.provider" class="shrink-0 text-xs" />
                  </div>
                  <span v-else>{{ slotProps.placeholder }}</span>
                </template>
                <template #option="{ option }">
                  <div class="flex min-w-0 items-center gap-2">
                    <span class="truncate">{{ option.label }}</span>
                    <ProviderTag :provider="option.provider" class="shrink-0 text-xs" />
                  </div>
                </template>
              </Select>
              <div v-if="formErrors.accountId" class="text-[13px] text-red-600">
                {{ formErrors.accountId }}
              </div>
              <div v-if="props.isEditing" class="text-[13px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.field.accountLockedHint') }}
              </div>
            </div>
          </div>

          <!-- 同步范围 -->
          <div v-else-if="step === 'scope'" key="scope" class="space-y-4 pt-2">
            <div class="space-y-2">
              <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                t('cronform.field.albums')
              }}</label>
              <MultiSelect
                v-model="form.albumIds"
                :options="formAlbumOptions"
                display="chip"
                optionLabel="label"
                optionValue="value"
                :placeholder="t('cronform.field.albumsPlaceholder')"
                class="w-full"
                filter
              />
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300">
                <Checkbox v-model="form.config.downloadImages" binary />
                <span>{{ t('cronform.toggle.downloadImages') }}</span>
              </div>
              <div class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300">
                <Checkbox v-model="form.config.downloadVideos" binary />
                <span>{{ t('cronform.toggle.downloadVideos') }}</span>
              </div>
            </div>

            <label class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300">
              <Checkbox v-model="form.config.downloadLivePhotos" binary />
              <span>{{ t('cronform.toggle.downloadLivePhotos') }}</span>
            </label>
            <p class="text-[12px] text-slate-500">
              {{ t('cronform.toggle.downloadLivePhotosHint') }}
            </p>

            <Message severity="info" variant="simple" icon="pi pi-info-circle">
              <i18n-t keypath="cronform.notice.text" tag="div" class="text-[13px]">
                <template #records>
                  <span class="font-semibold">{{ t('cronform.notice.records') }}</span>
                </template>
                <template #independent>
                  <span class="font-semibold">{{ t('cronform.notice.independent') }}</span>
                </template>
                <template #impact>
                  <span class="font-semibold">{{ t('cronform.notice.impact') }}</span>
                </template>
                <template #sameAsset>
                  <span class="font-semibold">{{ t('cronform.notice.sameAsset') }}</span>
                </template>
                <template #status>
                  <span class="font-semibold">{{ t('cronform.notice.status') }}</span>
                </template>
                <template #judged>
                  <span class="font-semibold">{{ t('cronform.notice.judged') }}</span>
                </template>
              </i18n-t>
            </Message>
          </div>

          <!-- 同步模式 -->
          <div v-else-if="step === 'mode'" key="mode" class="flex flex-col gap-5 pt-2">
            <Message severity="info" :closable="false">
              {{ t('cronform.retention.modesFixedHint') }}
            </Message>
            <fieldset class="space-y-3">
              <legend class="mb-2 text-[13px] font-medium">
                {{ t('cronform.retention.title') }}
              </legend>
              <label
                v-for="mirror in mirrorSupported ? [false, true] : [false]"
                :key="String(mirror)"
                :for="mirror ? 'retention-mirror' : 'retention-backup'"
                class="flex items-start gap-3 rounded-lg border p-3"
                :class="[
                  props.isEditing ? 'cursor-not-allowed' : 'cursor-pointer',
                  form.config.syncCloudDeletes === mirror
                    ? 'border-slate-400 bg-slate-50 dark:border-slate-500 dark:bg-slate-800'
                    : 'border-slate-200 dark:border-slate-700',
                ]"
              >
                <RadioButton
                  v-model="form.config.syncCloudDeletes"
                  :inputId="mirror ? 'retention-mirror' : 'retention-backup'"
                  name="local-retention"
                  :value="mirror"
                  :disabled="props.isEditing"
                  class="mt-0.5"
                />
                <span>
                  <span class="block font-medium">{{
                    t(mirror ? 'cronform.retention.mirror' : 'cronform.retention.backup')
                  }}</span>
                  <span class="mt-1 block text-[12px] text-slate-500 dark:text-slate-400">{{
                    t(mirror ? 'cronform.retention.mirrorHint' : 'cronform.retention.backupHint')
                  }}</span>
                </span>
              </label>
              <p v-if="!mirrorSupported" class="text-[12px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.retention.xiaomiCursorBackupOnly') }}
              </p>
            </fieldset>

            <div class="space-y-2 rounded-lg border border-slate-200 p-3 dark:border-slate-700">
              <div class="flex items-center justify-between gap-3">
                <label for="synology-photos" class="font-medium">{{
                  t('cronform.toggle.synologyPhotos')
                }}</label>
                <ToggleSwitch
                  :key="photosSwitchKey"
                  :modelValue="form.config.synologyPhotos"
                  inputId="synology-photos"
                  @update:modelValue="setSynologyPhotos"
                />
              </div>
              <p class="text-[12px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.toggle.synologyPhotosHint') }}
              </p>
              <p class="text-[12px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.toggle.synologyPhotosOffHint') }}
              </p>
              <Message severity="warn" :closable="false">
                {{
                  t(
                    form.config.synologyPhotos
                      ? 'cronform.toggle.synologyPhotosEnableHint'
                      : 'cronform.toggle.synologyPhotosDisableHint',
                  )
                }}
              </Message>
            </div>

            <div class="space-y-1">
              <h3 class="text-[13px] font-medium">{{ t('cronform.retention.scanTitle') }}</h3>
              <p class="text-[12px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.retention.scanHint') }}
              </p>
            </div>
            <OptionCard
              v-for="card in syncModeCards"
              :key="card.value"
              :icon="card.icon"
              :label="card.label"
              :desc="card.desc"
              :selected="form.syncMode === card.value"
              :disabled="props.isEditing"
              @click="selectSyncMode(card.value)"
            >
              <template v-if="card.badge" #badge>
                <Tag
                  :value="t(`cronform.badge.${card.badge}`)"
                  :severity="card.badge === 'beta' ? 'warn' : 'success'"
                  class="text-xs! px-1.5! py-0!"
                />
              </template>
            </OptionCard>
          </div>

          <!-- 调度与存储 -->
          <div v-else key="schedule" class="space-y-4 pt-2">
            <div class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300">
              <Checkbox v-model="form.enabled" binary />
              <span>{{ t('cronform.toggle.enabled') }}</span>
            </div>

            <div v-if="form.enabled" class="grid grid-cols-2 gap-4">
              <div class="space-y-2">
                <div class="flex items-center justify-between">
                  <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                    t('cronform.field.expression')
                  }}</label>
                  <Button
                    icon="pi pi-question-circle"
                    variant="text"
                    severity="secondary"
                    size="small"
                    class="w-6! h-6!"
                    @click="toggleCronHelp"
                  />
                </div>
                <InputText
                  v-model="form.config.expression"
                  placeholder="0 0 23 * * ?"
                  class="w-full"
                  @focus="openCronHelp"
                />

                <div v-if="formErrors.expression" class="text-[13px] text-red-600">
                  {{ formErrors.expression }}
                </div>
                <div class="text-[13px] text-slate-500 dark:text-slate-400">
                  {{ t('cronform.field.expressionHint') }}<br />{{
                    t('cronform.field.expressionExample')
                  }}
                </div>
              </div>
              <div class="space-y-2">
                <label
                  class="block text-[13px] font-medium text-slate-500 dark:text-slate-400 mb-4"
                  >{{ t('cronform.field.timeZone') }}</label
                >
                <Select
                  v-model="form.config.timeZone"
                  :options="timeZones"
                  placeholder="Asia/Shanghai"
                  filter
                  class="w-full"
                />
                <div v-if="formErrors.timeZone" class="text-[13px] text-red-600">
                  {{ formErrors.timeZone }}
                </div>
              </div>
            </div>

            <div class="space-y-2">
              <label class="block text-[13px] font-medium text-slate-500 dark:text-slate-400">{{
                t('cronform.field.targetPath')
              }}</label>
              <InputText v-model="form.config.targetPath" placeholder="/photos" class="w-full" />
              <div v-if="formErrors.targetPath" class="text-[13px] text-red-600">
                {{ formErrors.targetPath }}
              </div>
              <div class="text-[13px] text-slate-500 dark:text-slate-400">
                {{ t('cronform.field.targetPathPhotosHint') }}
              </div>
              <Message
                v-if="props.targetPathMountWarning"
                severity="warn"
                variant="simple"
                icon="pi pi-exclamation-triangle"
              >
                <div class="text-[13px]">
                  {{ t('cronform.field.targetPathMountWarning') }}
                </div>
              </Message>
            </div>

            <div
              class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300 pt-1"
            >
              <Checkbox v-model="form.config.notify" binary />
              <span>{{ t('cronform.toggle.notify') }}</span>
            </div>

            <Panel :header="t('cronform.advanced.title')" toggleable collapsed>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div class="space-y-1">
                  <div
                    class="flex items-center gap-2 text-[13px] text-slate-600 dark:text-slate-300"
                  >
                    <ToggleSwitch v-model="form.config.skipExistingFile" />
                    <span>{{ t('cronform.advanced.skipExistingFile') }}</span>
                  </div>
                  <div class="text-[13px] text-slate-500 dark:text-slate-400">
                    {{ t('cronform.advanced.skipExistingFileHint') }}
                  </div>
                </div>
              </div>

              <Panel :header="t('cronform.concurrency.title')" toggleable collapsed class="mt-4">
                <div class="text-[13px] text-slate-500 dark:text-slate-400 mb-4">
                  {{ t('cronform.concurrency.warning') }}
                </div>

                <div class="grid grid-cols-2 sm:grid-cols-3 gap-4">
                  <div class="space-y-2">
                    <label
                      class="block text-[13px] font-medium text-slate-500 dark:text-slate-400"
                      >{{ t('cronform.concurrency.fetchFromDbSize') }}</label
                    >
                    <InputNumber
                      v-model="form.config.fetchFromDbSize"
                      :min="1"
                      :max="20"
                      showButtons
                      buttonLayout="horizontal"
                      inputClass="w-12 text-center"
                      class="w-full"
                    >
                      <template>
                        <span class="pi pi-plus" />
                      </template>
                      <template>
                        <span class="pi pi-minus" />
                      </template>
                    </InputNumber>
                    <div class="text-[13px] text-slate-500 dark:text-slate-400">
                      {{ t('cronform.concurrency.fetchFromDbSizeHint') }}
                    </div>
                    <div v-if="formErrors.concurrency" class="text-[13px] text-red-600">
                      {{ formErrors.concurrency }}
                    </div>
                  </div>

                  <div class="space-y-2">
                    <label
                      class="block text-[13px] font-medium text-slate-500 dark:text-slate-400"
                      >{{ t('cronform.concurrency.downloaders') }}</label
                    >
                    <InputNumber
                      v-model="form.config.downloaders"
                      :min="1"
                      :max="50"
                      showButtons
                      buttonLayout="horizontal"
                      inputClass="w-12 text-center"
                      class="w-full"
                    >
                      <template>
                        <span class="pi pi-plus" />
                      </template>
                      <template>
                        <span class="pi pi-minus" />
                      </template>
                    </InputNumber>
                    <div class="text-[13px] text-slate-500 dark:text-slate-400">
                      {{ t('cronform.concurrency.downloadersHint') }}
                    </div>
                  </div>

                  <div class="space-y-2">
                    <label
                      class="block text-[13px] font-medium text-slate-500 dark:text-slate-400"
                      >{{ t('cronform.concurrency.verifiers') }}</label
                    >
                    <InputNumber
                      v-model="form.config.verifiers"
                      :min="1"
                      :max="50"
                      showButtons
                      buttonLayout="horizontal"
                      inputClass="w-12 text-center"
                      class="w-full"
                    >
                      <template>
                        <span class="pi pi-plus" />
                      </template>
                      <template>
                        <span class="pi pi-minus" />
                      </template>
                    </InputNumber>
                    <div class="text-[13px] text-slate-500 dark:text-slate-400">
                      {{ t('cronform.concurrency.verifiersHint') }}
                    </div>
                  </div>

                  <div class="space-y-2">
                    <label
                      class="block text-[13px] font-medium text-slate-500 dark:text-slate-400"
                      >{{ t('cronform.concurrency.exifProcessors') }}</label
                    >
                    <InputNumber
                      v-model="form.config.exifProcessors"
                      :min="1"
                      :max="50"
                      showButtons
                      buttonLayout="horizontal"
                      inputClass="w-12 text-center"
                      class="w-full"
                    >
                      <template>
                        <span class="pi pi-plus" />
                      </template>
                      <template>
                        <span class="pi pi-minus" />
                      </template>
                    </InputNumber>
                    <div class="text-[13px] text-slate-500 dark:text-slate-400">
                      {{ t('cronform.concurrency.exifProcessorsHint') }}
                    </div>
                  </div>
                </div>
              </Panel>
            </Panel>
          </div>
        </Transition>
      </div>

      <div
        v-if="showCronHelp"
        class="flex-1 border-l border-slate-100 dark:border-slate-800 pl-8 hidden lg:block min-w-0 overflow-y-auto"
      >
        <CronHelp v-if="showCronHelp" />
      </div>
    </div>

    <template #footer>
      <div class="flex items-center w-full mt-4">
        <Button
          v-if="stepIndex > 0"
          :label="t('common.action.back')"
          icon="pi pi-arrow-left"
          severity="secondary"
          text
          @click="goBack"
        />
        <div class="flex items-center justify-end gap-2 flex-1">
          <Button
            :label="t('common.action.cancel')"
            severity="secondary"
            text
            @click="closeDialog"
          />
          <Button
            v-if="!isLastStep"
            :label="t('common.action.next')"
            icon="pi pi-arrow-right"
            iconPos="right"
            severity="primary"
            @click="nextStep"
          />
          <Button
            v-else
            :label="props.isEditing ? t('common.action.save') : t('common.action.create')"
            :loading="props.saving"
            severity="primary"
            @click="emit('submit')"
          />
        </div>
      </div>
    </template>
  </Dialog>
</template>
