import { z } from "zod";
import { tokenRepository } from ".";
import { InstanceSchema } from "../models/instance"

export class InstanceRepository {

    getInstances = async () => {
        const res = await fetch("/api/admin/instances", {
            headers: {
              "Authorization": `Bearer ${tokenRepository.getToken()}`
            }
        })
        const result =  await z.array(InstanceSchema).safeParseAsync(await res.json());
        if (result.success) {
            return result.data
        } else {
            throw result.error
        }
    }
    
    approve = async (instanceId: string) => {
        await fetch(`/api/admin/instances/${instanceId}/approve`, {
            headers: {
              "Authorization": `Bearer ${tokenRepository.getToken()}`
            },
            method: "POST"
        })
    }
}

const instanceRepository = new InstanceRepository()

export {instanceRepository}